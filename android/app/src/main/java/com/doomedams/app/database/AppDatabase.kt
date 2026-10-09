package com.doomedams.app.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.doomedams.app.database.dao.AuditEventDao
import com.doomedams.app.database.dao.CandidateResponseDao
import com.doomedams.app.database.dao.ExamAttemptDao
import com.doomedams.app.database.dao.ExamDao
import com.doomedams.app.database.dao.IncidentDao
import com.doomedams.app.database.dao.IntegrityEventDao
import com.doomedams.app.database.dao.SyncOutboxDao
import com.doomedams.app.database.dao.UserCredentialDao
import com.doomedams.app.database.dao.UserDao
import com.doomedams.app.database.entity.AuditEventEntity
import com.doomedams.app.database.entity.CandidateResponseEntity
import com.doomedams.app.database.entity.ExamAttemptEntity
import com.doomedams.app.database.entity.ExamEntity
import com.doomedams.app.database.entity.IncidentCaseEntity
import com.doomedams.app.database.entity.IncidentDecisionEntity
import com.doomedams.app.database.entity.IntegrityEventEntity
import com.doomedams.app.database.entity.PolicyActionEntity
import com.doomedams.app.database.entity.SyncOutboxEntity
import com.doomedams.app.database.entity.UserCredentialEntity
import com.doomedams.app.database.entity.UserEntity

@Database(
    entities = [
        UserEntity::class,
        UserCredentialEntity::class,
        ExamEntity::class,
        ExamAttemptEntity::class,
        CandidateResponseEntity::class,
        IntegrityEventEntity::class,
        IncidentCaseEntity::class,
        IncidentDecisionEntity::class,
        PolicyActionEntity::class,
        AuditEventEntity::class,
        SyncOutboxEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun userCredentialDao(): UserCredentialDao
    abstract fun examDao(): ExamDao
    abstract fun examAttemptDao(): ExamAttemptDao
    abstract fun candidateResponseDao(): CandidateResponseDao
    abstract fun integrityEventDao(): IntegrityEventDao
    abstract fun incidentDao(): IncidentDao
    abstract fun auditEventDao(): AuditEventDao
    abstract fun syncOutboxDao(): SyncOutboxDao

    companion object {
        const val DATABASE_NAME = "doomedams.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addCallback(DatabaseSecurityCallback)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Enforces foreign key constraints and creates database triggers
         * preventing any UPDATE or DELETE operations on the audit log.
         */
        val DatabaseSecurityCallback = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL("PRAGMA foreign_keys = ON;")
                createAuditImmutabilityTriggers(db)
            }

            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                db.execSQL("PRAGMA foreign_keys = ON;")
                createAuditImmutabilityTriggers(db)
            }

            private fun createAuditImmutabilityTriggers(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS trg_prevent_audit_update
                    BEFORE UPDATE ON audit_events
                    BEGIN
                        SELECT RAISE(ABORT, 'IMMUTABILITY_VIOLATION: Audit event records cannot be modified.');
                    END;
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS trg_prevent_audit_delete
                    BEFORE DELETE ON audit_events
                    BEGIN
                        SELECT RAISE(ABORT, 'IMMUTABILITY_VIOLATION: Audit event records cannot be deleted.');
                    END;
                    """.trimIndent()
                )
            }
        }
    }
}

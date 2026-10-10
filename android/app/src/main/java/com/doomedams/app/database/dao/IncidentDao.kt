package com.doomedams.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.doomedams.app.database.entity.IncidentCaseEntity
import com.doomedams.app.database.entity.IncidentDecisionEntity
import com.doomedams.app.database.entity.PolicyActionEntity

@Dao
interface IncidentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIncident(incident: IncidentCaseEntity)

    @Query("SELECT * FROM incident_cases WHERE incidentId = :incidentId")
    suspend fun getIncidentById(incidentId: String): IncidentCaseEntity?

    @Query("SELECT * FROM incident_cases WHERE attemptId = :attemptId")
    suspend fun getIncidentsByAttempt(attemptId: String): List<IncidentCaseEntity>

    @Query("SELECT * FROM incident_cases ORDER BY createdAt DESC")
    suspend fun getAllIncidents(): List<IncidentCaseEntity>

    @Query("SELECT * FROM incident_cases WHERE examId = :examId ORDER BY createdAt DESC")
    suspend fun getIncidentsByExam(examId: String): List<IncidentCaseEntity>

    @Query("SELECT * FROM incident_cases WHERE studentId = :studentId ORDER BY createdAt DESC")
    suspend fun getIncidentsByStudent(studentId: String): List<IncidentCaseEntity>

    @Query("SELECT * FROM incident_cases WHERE incidentStatus = :status ORDER BY createdAt DESC")
    suspend fun getIncidentsByStatus(status: String): List<IncidentCaseEntity>

    @Update
    suspend fun updateIncident(incident: IncidentCaseEntity)

    // Decisions (Append-only history)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDecision(decision: IncidentDecisionEntity)

    @Query("SELECT * FROM incident_decisions WHERE incidentId = :incidentId ORDER BY createdAt ASC")
    suspend fun getDecisionsForIncident(incidentId: String): List<IncidentDecisionEntity>

    // Policy Actions
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPolicyAction(action: PolicyActionEntity)

    @Query("SELECT * FROM policy_actions WHERE incidentId = :incidentId ORDER BY createdAt ASC")
    suspend fun getPolicyActionsForIncident(incidentId: String): List<PolicyActionEntity>

    @Query("UPDATE policy_actions SET executionStatus = :status, completedAt = :completedAt WHERE actionId = :actionId")
    suspend fun updatePolicyActionStatus(actionId: String, status: String, completedAt: String?)

    // Appeals
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAppeal(appeal: com.doomedams.app.database.entity.AppealEntity)

    @Query("SELECT * FROM incident_appeals WHERE appealId = :appealId")
    suspend fun getAppealById(appealId: String): com.doomedams.app.database.entity.AppealEntity?

    @Query("SELECT * FROM incident_appeals WHERE incidentId = :incidentId ORDER BY submittedAt DESC")
    suspend fun getAppealsForIncident(incidentId: String): List<com.doomedams.app.database.entity.AppealEntity>

    @Query("SELECT * FROM incident_appeals WHERE studentId = :studentId ORDER BY submittedAt DESC")
    suspend fun getAppealsByStudent(studentId: String): List<com.doomedams.app.database.entity.AppealEntity>

    @Query("SELECT * FROM incident_appeals ORDER BY submittedAt DESC")
    suspend fun getAllAppeals(): List<com.doomedams.app.database.entity.AppealEntity>

    @Update
    suspend fun updateAppeal(appeal: com.doomedams.app.database.entity.AppealEntity)
}

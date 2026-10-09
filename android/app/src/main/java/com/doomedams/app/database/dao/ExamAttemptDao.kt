package com.doomedams.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.doomedams.app.database.entity.ExamAttemptEntity

@Dao
interface ExamAttemptDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttempt(attempt: ExamAttemptEntity)

    @Query("SELECT * FROM exam_attempts WHERE attemptId = :attemptId")
    suspend fun getAttemptById(attemptId: String): ExamAttemptEntity?

    @Query("SELECT * FROM exam_attempts WHERE examId = :examId")
    suspend fun getAttemptsByExam(examId: String): List<ExamAttemptEntity>

    @Query("UPDATE exam_attempts SET attemptStatus = :status, updatedAt = :updatedAt WHERE attemptId = :attemptId")
    suspend fun updateAttemptStatus(attemptId: String, status: String, updatedAt: String)

    /**
     * Updates ONLY resultStatus (e.g. to 'HELD_PENDING_REVIEW').
     * Guaranteed never to alter attemptStatus.
     */
    @Query("UPDATE exam_attempts SET resultStatus = :resultStatus, updatedAt = :updatedAt WHERE attemptId = :attemptId")
    suspend fun updateResultStatus(attemptId: String, resultStatus: String, updatedAt: String)

    @Update
    suspend fun updateAttempt(attempt: ExamAttemptEntity)
}

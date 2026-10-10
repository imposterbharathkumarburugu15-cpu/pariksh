package com.doomedams.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.doomedams.app.database.entity.ExamResultEntity

@Dao
interface ExamResultDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResult(result: ExamResultEntity)

    @Update
    suspend fun updateResult(result: ExamResultEntity)

    @Query("SELECT * FROM exam_results WHERE resultId = :resultId")
    suspend fun getResultById(resultId: String): ExamResultEntity?

    @Query("SELECT * FROM exam_results WHERE attemptId = :attemptId")
    suspend fun getResultByAttemptId(attemptId: String): ExamResultEntity?

    @Query("SELECT * FROM exam_results WHERE examId = :examId")
    suspend fun getResultsByExamId(examId: String): List<ExamResultEntity>

    @Query("SELECT * FROM exam_results WHERE studentId = :studentId")
    suspend fun getResultsByStudentId(studentId: String): List<ExamResultEntity>

    @Query("SELECT * FROM exam_results")
    suspend fun getAllResults(): List<ExamResultEntity>

    @Query("UPDATE exam_results SET resultStatus = :newStatus, updatedAt = :updatedAt WHERE resultId = :resultId")
    suspend fun updateResultStatus(resultId: String, newStatus: String, updatedAt: String)

    @Query("UPDATE exam_results SET resultStatus = :newStatus, holdReason = :holdReason, updatedAt = :updatedAt WHERE attemptId = :attemptId")
    suspend fun applyResultHold(attemptId: String, newStatus: String, holdReason: String, updatedAt: String)
}

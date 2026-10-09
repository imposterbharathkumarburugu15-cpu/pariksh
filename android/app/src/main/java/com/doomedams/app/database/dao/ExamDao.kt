package com.doomedams.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.doomedams.app.database.entity.ExamEntity

@Dao
interface ExamDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExam(exam: ExamEntity)

    @Query("SELECT * FROM exams WHERE examId = :examId")
    suspend fun getExamById(examId: String): ExamEntity?

    @Query("SELECT * FROM exams WHERE instituteId = :instituteId ORDER BY createdAt DESC")
    suspend fun getExamsByInstitute(instituteId: String): List<ExamEntity>

    @Update
    suspend fun updateExam(exam: ExamEntity)
}

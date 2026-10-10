package com.doomedams.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.doomedams.app.database.entity.ValidationIssueEntity

@Dao
interface ValidationIssueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIssues(issues: List<ValidationIssueEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIssue(issue: ValidationIssueEntity)

    @Query("SELECT * FROM validation_issues WHERE examId = :examId")
    suspend fun getIssuesByExamId(examId: String): List<ValidationIssueEntity>

    @Query("SELECT * FROM validation_issues WHERE examId = :examId AND severity = 'ERROR' AND isResolved = 0")
    suspend fun getUnresolvedErrors(examId: String): List<ValidationIssueEntity>

    @Query("DELETE FROM validation_issues WHERE examId = :examId")
    suspend fun deleteIssuesByExamId(examId: String)

    @Update
    suspend fun updateIssue(issue: ValidationIssueEntity)
}

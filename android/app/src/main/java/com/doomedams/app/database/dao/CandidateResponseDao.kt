package com.doomedams.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.doomedams.app.database.entity.CandidateResponseEntity

@Dao
interface CandidateResponseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertResponse(response: CandidateResponseEntity)

    @Query("SELECT * FROM candidate_responses WHERE attemptId = :attemptId AND questionId = :questionId")
    suspend fun getResponse(attemptId: String, questionId: String): CandidateResponseEntity?

    @Query("SELECT * FROM candidate_responses WHERE attemptId = :attemptId ORDER BY savedAt ASC")
    suspend fun getResponsesByAttempt(attemptId: String): List<CandidateResponseEntity>

    @Query("SELECT * FROM candidate_responses WHERE questionId = :questionId")
    suspend fun getResponsesByQuestionId(questionId: String): List<CandidateResponseEntity>
}

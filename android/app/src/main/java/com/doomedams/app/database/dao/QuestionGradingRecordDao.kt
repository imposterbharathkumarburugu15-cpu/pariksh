package com.doomedams.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.doomedams.app.database.entity.QuestionGradingRecordEntity

@Dao
interface QuestionGradingRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecords(records: List<QuestionGradingRecordEntity>)

    @Query("SELECT * FROM question_grading_records WHERE resultId = :resultId ORDER BY questionNumber ASC")
    suspend fun getRecordsByResultId(resultId: String): List<QuestionGradingRecordEntity>

    @Query("SELECT * FROM question_grading_records WHERE questionId = :questionId")
    suspend fun getRecordsByQuestionId(questionId: String): List<QuestionGradingRecordEntity>

    @Query("DELETE FROM question_grading_records WHERE resultId = :resultId")
    suspend fun deleteRecordsByResultId(resultId: String)
}

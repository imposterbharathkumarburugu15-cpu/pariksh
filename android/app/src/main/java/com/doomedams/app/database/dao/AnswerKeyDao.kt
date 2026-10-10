package com.doomedams.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.doomedams.app.database.entity.AnswerKeyEntity

@Dao
interface AnswerKeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnswerKeys(answerKeys: List<AnswerKeyEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnswerKey(answerKey: AnswerKeyEntity)

    @Query("SELECT * FROM answer_keys WHERE examId = :examId")
    suspend fun getAnswerKeysByExamId(examId: String): List<AnswerKeyEntity>

    @Query("SELECT * FROM answer_keys WHERE questionId = :questionId")
    suspend fun getAnswerKeyByQuestionId(questionId: String): AnswerKeyEntity?

    @Query("DELETE FROM answer_keys WHERE examId = :examId")
    suspend fun deleteAnswerKeysByExamId(examId: String)

    @Update
    suspend fun updateAnswerKey(answerKey: AnswerKeyEntity)
}

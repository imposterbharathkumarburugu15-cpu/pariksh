package com.doomedams.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.doomedams.app.database.entity.QuestionEntity

@Dao
interface QuestionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestions(questions: List<QuestionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestion(question: QuestionEntity)

    @Query("SELECT * FROM questions WHERE examId = :examId ORDER BY questionNumber ASC")
    suspend fun getQuestionsByExamId(examId: String): List<QuestionEntity>

    @Query("SELECT * FROM questions WHERE questionId = :questionId")
    suspend fun getQuestionById(questionId: String): QuestionEntity?

    @Query("DELETE FROM questions WHERE examId = :examId")
    suspend fun deleteQuestionsByExamId(examId: String)

    @Update
    suspend fun updateQuestion(question: QuestionEntity)
}

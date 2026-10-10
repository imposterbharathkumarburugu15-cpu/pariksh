package com.doomedams.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.doomedams.app.database.entity.WorkedSolutionEntity

@Dao
interface WorkedSolutionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSolutions(solutions: List<WorkedSolutionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSolution(solution: WorkedSolutionEntity)

    @Query("SELECT * FROM worked_solutions WHERE examId = :examId")
    suspend fun getSolutionsByExamId(examId: String): List<WorkedSolutionEntity>

    @Query("SELECT * FROM worked_solutions WHERE questionId = :questionId")
    suspend fun getSolutionByQuestionId(questionId: String): WorkedSolutionEntity?

    @Query("DELETE FROM worked_solutions WHERE examId = :examId")
    suspend fun deleteSolutionsByExamId(examId: String)

    @Update
    suspend fun updateSolution(solution: WorkedSolutionEntity)
}

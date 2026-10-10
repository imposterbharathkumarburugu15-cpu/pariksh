package com.doomedams.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.doomedams.app.database.entity.MarkingSchemeEntity

@Dao
interface MarkingSchemeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarkingScheme(scheme: MarkingSchemeEntity)

    @Query("SELECT * FROM marking_schemes WHERE examId = :examId LIMIT 1")
    suspend fun getMarkingSchemeByExamId(examId: String): MarkingSchemeEntity?

    @Query("DELETE FROM marking_schemes WHERE examId = :examId")
    suspend fun deleteMarkingSchemeByExamId(examId: String)

    @Update
    suspend fun updateMarkingScheme(scheme: MarkingSchemeEntity)
}

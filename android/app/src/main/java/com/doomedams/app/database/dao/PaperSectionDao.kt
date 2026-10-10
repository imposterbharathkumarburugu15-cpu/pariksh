package com.doomedams.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.doomedams.app.database.entity.PaperSectionEntity

@Dao
interface PaperSectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSections(sections: List<PaperSectionEntity>)

    @Query("SELECT * FROM paper_sections WHERE examId = :examId ORDER BY sectionOrder ASC")
    suspend fun getSectionsByExamId(examId: String): List<PaperSectionEntity>

    @Query("DELETE FROM paper_sections WHERE examId = :examId")
    suspend fun deleteSectionsByExamId(examId: String)
}

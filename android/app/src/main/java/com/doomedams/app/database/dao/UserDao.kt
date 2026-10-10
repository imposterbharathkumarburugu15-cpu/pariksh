package com.doomedams.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.doomedams.app.database.entity.UserEntity

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUser(user: UserEntity)

    @Query("SELECT * FROM users WHERE userId = :userId")
    suspend fun getUserById(userId: String): UserEntity?

    @Query("SELECT * FROM users WHERE instituteId = :instituteId")
    suspend fun getUsersByInstitute(instituteId: String): List<UserEntity>

    @Query("SELECT * FROM users")
    suspend fun getAllUsers(): List<UserEntity>
}

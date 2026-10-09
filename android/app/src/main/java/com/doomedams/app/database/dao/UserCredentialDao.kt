package com.doomedams.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.doomedams.app.database.entity.UserCredentialEntity

@Dao
interface UserCredentialDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCredential(credential: UserCredentialEntity)

    @Query("SELECT * FROM user_credentials WHERE userId = :userId")
    suspend fun getCredentialByUserId(userId: String): UserCredentialEntity?

    @Update
    suspend fun updateCredential(credential: UserCredentialEntity)

    @Query("DELETE FROM user_credentials WHERE userId = :userId")
    suspend fun deleteCredential(userId: String)
}

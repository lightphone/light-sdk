package com.thelightphone.sdk.server

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "push_registrations")
data class PushRegistration(
    @PrimaryKey val token: String,
    val packageName: String,
    val endpoint: String,
    val channel: String? = null,
    val vapid: String? = null,
    /** Epoch millis the row was first written. Preserved across re-registrations. */
    val firstInsertedAt: Long = 0,
    /** Epoch millis of the most recent registration call. Updated on every upsert. */
    val lastRegisteredAt: Long = 0,
)

@Dao
internal interface PushRegistrationDao {
    @Query(
        """
        INSERT INTO push_registrations
            (token, packageName, endpoint, channel, vapid, firstInsertedAt, lastRegisteredAt)
        VALUES
            (:token, :packageName, :endpoint, :channel, :vapid, :now, :now)
        ON CONFLICT(token) DO UPDATE SET
            packageName = excluded.packageName,
            endpoint = excluded.endpoint,
            channel = excluded.channel,
            vapid = excluded.vapid,
            lastRegisteredAt = :now
        """
    )
    suspend fun upsert(
        token: String,
        packageName: String,
        endpoint: String,
        channel: String?,
        vapid: String?,
        now: Long,
    )

    @Query("SELECT * FROM push_registrations WHERE token = :token")
    suspend fun findByToken(token: String): PushRegistration?

    @Query("SELECT * FROM push_registrations WHERE endpoint = :endpoint")
    suspend fun findByEndpoint(endpoint: String): PushRegistration?

    @Query("DELETE FROM push_registrations WHERE token = :token")
    suspend fun deleteByToken(token: String): Int

    @Query("DELETE FROM push_registrations WHERE packageName = :packageName")
    suspend fun deleteByPackage(packageName: String): Int

    @Query("SELECT * FROM push_registrations WHERE packageName = :packageName AND channel = :channel LIMIT 1")
    suspend fun findByPackageAndChannel(packageName: String, channel: String): PushRegistration?
}

@Database(entities = [PushRegistration::class], version = 1, exportSchema = false)
internal abstract class LightPushDb : RoomDatabase() {
    abstract fun pushRegistrations(): PushRegistrationDao
}

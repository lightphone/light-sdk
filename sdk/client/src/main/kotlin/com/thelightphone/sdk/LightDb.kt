package com.thelightphone.sdk

import androidx.room.Room
import androidx.room.RoomDatabase

fun <T : RoomDatabase> SealedLightContext.buildDatabase(
    dbClass: Class<T>,
    dbName: String?,
    configure: RoomDatabase.Builder<T>.() -> RoomDatabase.Builder<T> = { this },
): T = Room.databaseBuilder(androidContext.applicationContext, dbClass, dbName).configure().build()
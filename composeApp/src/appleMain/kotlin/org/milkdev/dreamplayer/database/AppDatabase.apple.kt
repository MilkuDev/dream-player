package org.milkdev.dreamplayer.database

import androidx.room.RoomDatabaseConstructor

@Suppress(names = ["NO_ACTUAL_FOR_EXPECT"])
actual object AppDatabaseConstructor :
    RoomDatabaseConstructor<AppDatabase> {
    actual override fun initialize(): AppDatabase {
        TODO("Not yet implemented")
    }
}
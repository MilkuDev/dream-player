package org.milkdev.dreamplayer.library

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.milkdev.dreamplayer.database.AppDatabase
import org.milkdev.dreamplayer.database.AppDatabaseConstructor

actual fun createInMemoryTestDatabase(): AppDatabase {
    return Room.inMemoryDatabaseBuilder<AppDatabase>(
        factory = AppDatabaseConstructor::initialize
    )
        .setDriver(BundledSQLiteDriver())
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .fallbackToDestructiveMigration(dropAllTables = false)
        .build()
}

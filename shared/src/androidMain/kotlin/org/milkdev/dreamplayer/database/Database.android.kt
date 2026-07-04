package org.milkdev.dreamplayer.database

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.milkdev.dreamplayer.app.applicationContext
import org.milkdev.org.milkdev.dreamplayer.database.AppDatabase
import org.milkdev.org.milkdev.dreamplayer.database.AppDatabaseConstructor
import org.milkdev.org.milkdev.dreamplayer.database.Migration1To2
import org.milkdev.org.milkdev.dreamplayer.database.Migration2To3
import org.milkdev.org.milkdev.dreamplayer.database.Migration3To4
import org.milkdev.org.milkdev.dreamplayer.database.Migration4To5
import org.milkdev.org.milkdev.dreamplayer.database.Migration5To6
import org.milkdev.org.milkdev.dreamplayer.database.Migration6To7
import org.milkdev.org.milkdev.dreamplayer.database.Migration7To8

actual val appDatabase: AppDatabase = run {
    val dbFile = applicationContext.getDatabasePath("dreamplayer.db")

    Room.databaseBuilder<AppDatabase>(
        context = applicationContext,
        name = dbFile.absolutePath,
        factory = AppDatabaseConstructor::initialize
    )
        .setDriver(BundledSQLiteDriver())
        .addMigrations(
            Migration1To2,
            Migration2To3,
            Migration3To4,
            Migration4To5,
            Migration5To6,
            Migration6To7,
            Migration7To8
        )
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build()
}

package org.milkdev.dreamplayer.database

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import okio.Path.Companion.toPath

actual val appDatabase: AppDatabase = run {
    val dbFilepath = applicationDirectory.toPath().toNioPath()
        .resolve("dreamplayer.db")
        .toAbsolutePath()
        .toString()

    Room.databaseBuilder<AppDatabase>(
        name = dbFilepath,
        factory = AppDatabaseConstructor::initialize
    )
        .setDriver(BundledSQLiteDriver())
        .addMigrations(Migration1To2, Migration2To3, Migration3To4, Migration4To5, Migration5To6, Migration6To7, Migration7To8)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build()
}

package org.milkdev.dreamplayer.library

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.milkdev.dreamplayer.database.AppDatabase
import org.milkdev.dreamplayer.database.dao.MusicDao
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

expect fun createInMemoryTestDatabase(): AppDatabase

abstract class BaseDatabaseTest {

    protected lateinit var db: AppDatabase
    protected lateinit var musicDao: MusicDao

    @BeforeTest
    fun setUpDatabase() {
        db = createInMemoryTestDatabase()
        musicDao = db.musicDao()
    }

    @AfterTest
    fun tearDownDatabase() {
        db.close()
    }

    protected fun runTestInScope(block: suspend TestScope.() -> Unit) {
        runTest { block() }
    }
}

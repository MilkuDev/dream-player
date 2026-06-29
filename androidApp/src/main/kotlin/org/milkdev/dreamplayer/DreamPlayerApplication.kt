package org.milkdev.dreamplayer

import android.app.Application
import org.milkdev.dreamplayer.app.initializeApplicationContext

class DreamPlayerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initializeApplicationContext(applicationContext)
    }
}

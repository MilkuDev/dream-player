package org.milkdev.dreamplayer.app

import android.content.Context

private var androidApplicationContext: Context? = null

val applicationContext: Context
    get() = androidApplicationContext ?: error("Application context is not initialized. Ensure that you called initializeApplicationContext() in your Application class")

fun initializeApplicationContext(value: Context) {
    androidApplicationContext = value
}

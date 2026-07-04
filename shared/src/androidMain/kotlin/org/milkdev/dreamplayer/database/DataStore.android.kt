package org.milkdev.dreamplayer.database

import org.milkdev.dreamplayer.app.applicationContext

internal actual val SETTINGS_DATASTORE_FILE_PATH: String =
    applicationContext.filesDir
        .resolve("datastore/settings.preferences_pb")
        .absolutePath

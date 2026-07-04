package org.milkdev.dreamplayer.database

import okio.Path.Companion.toPath

internal actual val SETTINGS_DATASTORE_FILE_PATH: String =
    applicationDirectory.toPath().toNioPath()
        .resolve("settings.preferences_pb")
        .toAbsolutePath()
        .toString()

package org.milkdev.dreamplayer.database

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import okio.Path.Companion.toPath

// Android: /data/data/org.milkdev.dreamplayer/files/settings.preferences_pb
// Windows: /
internal expect val SETTINGS_DATASTORE_FILE_PATH: String

val settingsDataStore = PreferenceDataStoreFactory.createWithPath(
    produceFile = { SETTINGS_DATASTORE_FILE_PATH.toPath() }
)

enum class DailyPlaylistGenerationMode {
    LOCAL_DAILY,
    AI_API,
}

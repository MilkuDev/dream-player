package org.milkdev.dreamplayer.database

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import okio.Path.Companion.toPath
import java.io.File

// Android: /data/data/org.milkdev.dreamplayer/files/settings.preferences_pb
// Windows: /
internal expect val SETTINGS_DATASTORE_FILE_PATH: String

val settingsDataStore = PreferenceDataStoreFactory.createWithPath(
    produceFile = { File(SETTINGS_DATASTORE_FILE_PATH).absolutePath.toPath() }
)

enum class DailyPlaylistGenerationMode {
    LOCAL_DAILY,
    AI_API,
}

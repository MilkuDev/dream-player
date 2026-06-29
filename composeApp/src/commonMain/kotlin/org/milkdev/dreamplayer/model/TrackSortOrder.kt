package org.milkdev.dreamplayer.model

import org.jetbrains.compose.resources.DrawableResource
import org.milkdev.dreamplayer.generated.resources.Res
import org.milkdev.dreamplayer.generated.resources.album
import org.milkdev.dreamplayer.generated.resources.music_note
import org.milkdev.dreamplayer.generated.resources.artist

interface LibrarySortOrder {
    val label: String
    val icon: DrawableResource
}

enum class TrackSortOrder(
    override val label: String,
    override val icon: DrawableResource,
) : LibrarySortOrder {
    TRACK_NAME("Название", Res.drawable.music_note),
    ARTIST("Исполнитель", Res.drawable.artist),
    ALBUM("Альбом", Res.drawable.album),
    YEAR("Год", Res.drawable.album),
    GENRE("Жанр", Res.drawable.music_note)
}

enum class AlbumSortOrder(
    override val label: String,
    override val icon: DrawableResource,
) : LibrarySortOrder {
    TITLE("Название", Res.drawable.album),
    ARTIST("Исполнитель", Res.drawable.artist),
    YEAR("Год", Res.drawable.album),
    GENRE("Жанр", Res.drawable.music_note)
}

package org.milkdev.dreamplayer.model


interface LibrarySortOrder {
    val label: String
}

enum class TrackSortOrder(
    override val label: String,
) : LibrarySortOrder {
    TRACK_NAME("Название"),
    ARTIST("Исполнитель"),
    ALBUM("Альбом"),
    YEAR("Год"),
    GENRE("Жанр"),
}

enum class AlbumSortOrder(
    override val label: String,
) : LibrarySortOrder {
    TITLE("Название"),
    ARTIST("Исполнитель"),
    YEAR("Год"),
    GENRE("Жанр"),
}

package org.milkdev.dreamplayer.extensions.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class LastFmAlbumResponse(
    val album: LastFmAlbum? = null
)

@Serializable
data class LastFmAlbum(
    val name: String,
    val artist: String,
    val mbid: String? = null,
    val image: List<LastFmImage>? = null,
    val releasedate: String? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    val listeners: String = "",
    @Serializable(with = FlexibleStringSerializer::class)
    val playcount: String = "",
    val toptags: LastFmTags? = null,
    val wiki: LastFmWiki? = null
)

@Serializable
data class LastFmTags(
    @Serializable(with = LastFmTagListSerializer::class)
    val tag: List<LastFmTag> = emptyList()
)

@Serializable
data class LastFmTag(
    val name: String,
    val url: String = "",
    @Serializable(with = FlexibleIntSerializer::class)
    val count: Int? = null,
)

@Serializable
data class LastFmWiki(
    val published: String? = null,
    val summary: String? = null,
    val content: String? = null
)


@Serializable
data class LastFmImage(
    @SerialName("#text") val url: String,
    val size: String // "small", "medium", "large", "extralarge", "mega"
)

@Serializable
data class LastFmAlbumSearchResponse(
    val results: LastFmAlbumSearchResults? = null,
)

@Serializable
data class LastFmAlbumSearchResults(
    val albummatches: LastFmAlbumMatches? = null,
)

@Serializable
data class LastFmAlbumMatches(
    @Serializable(with = LastFmAlbumSearchItemListSerializer::class)
    val album: List<LastFmAlbumSearchItem> = emptyList(),
)

@Serializable
data class LastFmAlbumSearchItem(
    val name: String = "",
    val artist: String = "",
    val mbid: String? = null,
    val image: List<LastFmImage>? = null,
)

object LastFmTagListSerializer : KSerializer<List<LastFmTag>> {
    override val descriptor: SerialDescriptor = ListSerializer(LastFmTag.serializer()).descriptor

    override fun deserialize(decoder: Decoder): List<LastFmTag> {
        val input = decoder as? JsonDecoder ?: return emptyList()
        return input.decodeJsonElement().decodeFlexibleList(input, LastFmTag.serializer())
    }

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: List<LastFmTag>) {
        error("Last.fm DTOs are read-only")
    }
}

object LastFmAlbumSearchItemListSerializer : KSerializer<List<LastFmAlbumSearchItem>> {
    override val descriptor: SerialDescriptor = ListSerializer(LastFmAlbumSearchItem.serializer()).descriptor

    override fun deserialize(decoder: Decoder): List<LastFmAlbumSearchItem> {
        val input = decoder as? JsonDecoder ?: return emptyList()
        return input.decodeJsonElement().decodeFlexibleList(input, LastFmAlbumSearchItem.serializer())
    }

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: List<LastFmAlbumSearchItem>) {
        error("Last.fm DTOs are read-only")
    }
}

object FlexibleStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val input = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = input.decodeJsonElement()
        return (element as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: String) {
        encoder.encodeString(value)
    }
}

object FlexibleIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int? {
        val input = decoder as? JsonDecoder ?: return decoder.decodeInt()
        val element = input.decodeJsonElement()
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    }

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Int?) {
        error("Last.fm DTOs are read-only")
    }
}

private fun <T> JsonElement.decodeFlexibleList(
    input: JsonDecoder,
    serializer: KSerializer<T>,
): List<T> {
    return when (this) {
        is JsonArray -> mapNotNull { element ->
            runCatching { input.json.decodeFromJsonElement(serializer, element) }.getOrNull()
        }
        is JsonObject -> listOfNotNull(
            runCatching { input.json.decodeFromJsonElement(serializer, this) }.getOrNull()
        )
        else -> emptyList()
    }
}

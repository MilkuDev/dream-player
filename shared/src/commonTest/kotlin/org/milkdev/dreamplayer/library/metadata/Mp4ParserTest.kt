package org.milkdev.dreamplayer.library.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Mp4ParserTest {
    @Test
    fun parsesFreeformMusicBrainzTagsAndStandardDateAndGenre() {
        val recordingId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val releaseId = "11111111-2222-3333-4444-555555555555"
        val releaseGroupId = "66666666-7777-8888-9999-aaaaaaaaaaaa"
        val firstArtistId = "bbbbbbbb-1111-2222-3333-444444444444"
        val secondArtistId = "cccccccc-5555-6666-7777-888888888888"
        val albumArtistId = "dddddddd-9999-aaaa-bbbb-cccccccccccc"
        val file = mp4File(
            freeform("MusicBrainz Track Id", recordingId),
            freeform("MusicBrainz Album Id", releaseId),
            freeform("MusicBrainz Release Group Id", releaseGroupId),
            freeform("MusicBrainz Artist Id", firstArtistId),
            freeform("MusicBrainz Artist Id", secondArtistId),
            freeform("MusicBrainz Album Artist Id", albumArtistId),
            standardText(DATE_ATOM, "2021-09-17"),
            standardText(GENRE_ATOM, "Electronic"),
        )

        val tag = assertNotNull(file.parseMp4())
        val metadata = tag.toEmbeddedMetadata("fingerprint")

        assertTrue(tag.issues.isEmpty())
        assertEquals(recordingId, metadata.recordingMbid)
        assertEquals(releaseId, metadata.releaseMbid)
        assertEquals(releaseGroupId, metadata.releaseGroupMbid)
        assertEquals(listOf(firstArtistId, secondArtistId), metadata.artistMbids)
        assertEquals(listOf(albumArtistId), metadata.albumArtistMbids)
        assertEquals(2021, metadata.year)
        assertEquals(listOf("electronic"), metadata.genres)
    }

    @Test
    fun jumpsOverLargeMdatToExtendedSizeMoovWithoutReadingAudioPayload() {
        val recordingId = "01234567-89ab-cdef-0123-456789abcdef"
        val fileType = atom("ftyp", "M4A \u0000\u0000\u0000\u0000".encodeToByteArray())
        val movie = movieAtom(
            items = arrayOf(freeform("MusicBrainz Track Id", recordingId)),
            extendedSize = true,
        )
        val movieOffset = 128L * 1024L * 1024L
        val mediaDataSize = movieOffset - fileType.size
        val mediaDataHeader = bigEndian(mediaDataSize, 4) + atomType("mdat")
        val fileSize = movieOffset + movie.size
        val segments = listOf(
            Segment(0L, fileType),
            Segment(fileType.size.toLong(), mediaDataHeader),
            Segment(movieOffset, movie),
        )
        var requestedBytes = 0

        val tag = assertNotNull(
            parseMp4Tag(
                fileSize = fileSize,
                reader = Mp4RandomAccessReader { offset, byteCount ->
                    requestedBytes += byteCount
                    val segment = segments.firstOrNull { candidate ->
                        offset >= candidate.offset &&
                            offset + byteCount <= candidate.offset + candidate.bytes.size
                    } ?: error("Parser attempted to read mdat payload at $offset ($byteCount bytes)")
                    val localOffset = (offset - segment.offset).toInt()
                    segment.bytes.copyOfRange(localOffset, localOffset + byteCount)
                },
            )
        )

        assertEquals(recordingId, tag.toEmbeddedMetadata("fingerprint").recordingMbid)
        assertTrue(requestedBytes < 2048, "Parser requested $requestedBytes bytes")
    }

    @Test
    fun supportsQuickTimeMetaWithoutFullBoxPrefixAndUtf16Data() {
        val releaseId = "fedcba98-7654-3210-fedc-ba9876543210"
        val itemList = atom(
            "ilst",
            freeform(
                name = "MusicBrainz Album Id",
                value = releaseId,
                dataType = 2,
            ),
        )
        val quickTimeMeta = atom("meta", atom("hdlr", byteArrayOf()) + itemList)
        val file = atom("ftyp", "M4A ".encodeToByteArray()) +
            atom("moov", atom("udta", quickTimeMeta))

        val tag = assertNotNull(file.parseMp4())

        assertTrue(tag.issues.isEmpty())
        assertEquals(releaseId, tag.toEmbeddedMetadata("fingerprint").releaseMbid)
    }

    @Test
    fun doesNotTreatMusicBrainzTextInsideMdatAsMetadata() {
        val falseTag = (
            "MusicBrainz Track Id\u0000" +
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            ).encodeToByteArray()
        val file = atom("ftyp", "M4A ".encodeToByteArray()) +
            atom("mdat", falseTag) +
            atom("moov", atom("udta", atom("meta", byteArrayOf(0, 0, 0, 0))))

        val tag = assertNotNull(file.parseMp4())

        assertNull(tag.toEmbeddedMetadata("fingerprint").recordingMbid)
    }

    @Test
    fun oversizedValueIsSkippedWhileFollowingItemIsStillParsed() {
        val releaseId = "99999999-8888-7777-6666-555555555555"
        val oversized = freeform(
            name = "MusicBrainz Track Id",
            rawValue = ByteArray(65 * 1024) { 'a'.code.toByte() },
        )
        val file = mp4File(
            oversized,
            freeform("MusicBrainz Album Id", releaseId),
        )

        val tag = assertNotNull(file.parseMp4())

        assertTrue(Mp4ParseIssue.VALUE_TOO_LARGE in tag.issues)
        assertNull(tag.toEmbeddedMetadata("fingerprint").recordingMbid)
        assertEquals(releaseId, tag.toEmbeddedMetadata("fingerprint").releaseMbid)
    }

    private fun ByteArray.parseMp4(): Mp4Tag? {
        return parseMp4Tag(
            fileSize = size.toLong(),
            reader = Mp4RandomAccessReader { offset, byteCount ->
                if (offset < 0L || offset + byteCount > size) {
                    null
                } else {
                    copyOfRange(offset.toInt(), offset.toInt() + byteCount)
                }
            },
        )
    }

    private fun mp4File(vararg items: ByteArray): ByteArray {
        return atom("ftyp", "M4A \u0000\u0000\u0000\u0000".encodeToByteArray()) +
            movieAtom(items)
    }

    private fun movieAtom(
        items: Array<out ByteArray>,
        extendedSize: Boolean = false,
    ): ByteArray {
        val itemList = atom("ilst", items.concatenate())
        val metadata = atom(
            "meta",
            byteArrayOf(0, 0, 0, 0) +
                atom("hdlr", byteArrayOf()) +
                itemList,
        )
        return atom(
            type = "moov",
            payload = atom("udta", metadata),
            extendedSize = extendedSize,
        )
    }

    private fun freeform(
        name: String,
        value: String,
        dataType: Int = 1,
    ): ByteArray {
        val rawValue = if (dataType == 2) value.utf16BigEndian() else value.encodeToByteArray()
        return freeform(name, rawValue, dataType)
    }

    private fun freeform(
        name: String,
        rawValue: ByteArray,
        dataType: Int = 1,
    ): ByteArray {
        return atom(
            "----",
            fullBoxText("mean", "com.apple.iTunes") +
                fullBoxText("name", name) +
                dataAtom(rawValue, dataType),
        )
    }

    private fun standardText(type: ByteArray, value: String): ByteArray {
        return atom(type, dataAtom(value.encodeToByteArray(), dataType = 1))
    }

    private fun fullBoxText(type: String, value: String): ByteArray {
        return atom(type, byteArrayOf(0, 0, 0, 0) + value.encodeToByteArray())
    }

    private fun dataAtom(value: ByteArray, dataType: Int): ByteArray {
        return atom(
            "data",
            bigEndian(dataType.toLong(), 4) +
                byteArrayOf(0, 0, 0, 0) +
                value,
        )
    }

    private fun atom(
        type: String,
        payload: ByteArray,
        extendedSize: Boolean = false,
    ): ByteArray {
        return atom(atomType(type), payload, extendedSize)
    }

    private fun atom(
        type: ByteArray,
        payload: ByteArray,
        extendedSize: Boolean = false,
    ): ByteArray {
        return if (extendedSize) {
            bigEndian(1L, 4) +
                type +
                bigEndian(16L + payload.size, 8) +
                payload
        } else {
            bigEndian(8L + payload.size, 4) + type + payload
        }
    }

    private fun atomType(value: String): ByteArray {
        require(value.length == 4)
        return value.map { character -> character.code.toByte() }.toByteArray()
    }

    private fun bigEndian(value: Long, size: Int): ByteArray {
        return ByteArray(size) { index ->
            ((value shr ((size - index - 1) * 8)) and 0xFF).toByte()
        }
    }

    private fun String.utf16BigEndian(): ByteArray {
        return flatMap { character ->
            listOf(
                ((character.code shr 8) and 0xFF).toByte(),
                (character.code and 0xFF).toByte(),
            )
        }.toByteArray()
    }

    private fun Array<out ByteArray>.concatenate(): ByteArray {
        return fold(byteArrayOf()) { result, bytes -> result + bytes }
    }

    private data class Segment(
        val offset: Long,
        val bytes: ByteArray,
    )

    companion object {
        private val DATE_ATOM = byteArrayOf(0xA9.toByte(), 0x64, 0x61, 0x79)
        private val GENRE_ATOM = byteArrayOf(0xA9.toByte(), 0x67, 0x65, 0x6E)
    }
}

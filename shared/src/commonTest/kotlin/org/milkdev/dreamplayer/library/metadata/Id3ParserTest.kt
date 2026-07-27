package org.milkdev.dreamplayer.library.metadata

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Id3ParserTest {
    @Test
    fun id3v23SkipsExtendedHeaderAndReadsMetadataFrames() {
        val releaseId = "12345678-1234-1234-1234-1234567890ab"
        val extendedHeader = bigEndian(6, 4) +
            byteArrayOf(0, 0) +
            byteArrayOf(0, 0, 0, 0)
        val body = extendedHeader +
            v23Frame("TXXX", userTextPayload("MusicBrainz Album Id", releaseId)) +
            v23Frame("TYER", textPayload("1999")) +
            v23Frame("TCON", textPayload("Alternative"))

        val tag = assertNotNull(
            parseId3Tag(
                header = id3Header(version = 3, flags = 0x40, storedBodySize = body.size),
                body = body,
            )
        )
        val metadata = tag.toEmbeddedMetadata("fingerprint")

        assertTrue(tag.issues.isEmpty())
        assertEquals(releaseId, metadata.releaseMbid)
        assertEquals(1999, metadata.year)
        assertEquals(listOf("alternative"), metadata.genres)
    }

    @Test
    fun id3v23RemovesTagUnsynchronisationBeforeReadingFrames() {
        val image = byteArrayOf(
            0x01,
            0xFF.toByte(),
            0xE1.toByte(),
            0x02,
            0xFF.toByte(),
            0x00,
            0x03,
        )
        val decodedBody = v23Frame("APIC", picturePayload(image))
        val storedBody = decodedBody.unsynchronise()

        val tag = assertNotNull(
            parseId3Tag(
                header = id3Header(version = 3, flags = 0x80, storedBodySize = storedBody.size),
                body = storedBody,
            )
        )

        assertTrue(tag.issues.isEmpty())
        assertContentEquals(image, assertNotNull(tag.preferredPicture()).bytes)
    }

    @Test
    fun id3v24AppliesFrameUnsynchronisationAndDataLengthIndicator() {
        val image = byteArrayOf(0x11, 0xFF.toByte(), 0xE2.toByte(), 0x22)
        val decodedPayload = picturePayload(image)
        val storedPayload = synchsafe(decodedPayload.size) + decodedPayload.unsynchronise()
        val body = v24Frame(
            id = "APIC",
            payload = storedPayload,
            formatFlags = 0x03,
        )

        val tag = assertNotNull(
            parseId3Tag(
                header = id3Header(version = 4, flags = 0, storedBodySize = body.size),
                body = body,
            )
        )

        assertTrue(tag.issues.isEmpty())
        assertContentEquals(image, assertNotNull(tag.preferredPicture()).bytes)
    }

    @Test
    fun ufidAndRepeatedUserTextValuesArePreserved() {
        val recordingId = "aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb"
        val firstArtistId = "11111111-2222-3333-4444-555555555555"
        val secondArtistId = "66666666-7777-8888-9999-aaaaaaaaaaaa"
        val ufidPayload = "http://musicbrainz.org".encodeToByteArray() +
            byteArrayOf(0) +
            recordingId.encodeToByteArray()
        val body = v23Frame("UFID", ufidPayload) +
            v23Frame("TXXX", userTextPayload("MusicBrainz Artist Id", firstArtistId)) +
            v23Frame("TXXX", userTextPayload("MusicBrainz Artist Id", secondArtistId))

        val tag = assertNotNull(
            parseId3Tag(
                header = id3Header(version = 3, flags = 0, storedBodySize = body.size),
                body = body,
            )
        )
        val metadata = tag.toEmbeddedMetadata("fingerprint")

        assertEquals(recordingId, metadata.recordingMbid)
        assertEquals(listOf(firstArtistId, secondArtistId), metadata.artistMbids)
        assertEquals(
            listOf(firstArtistId, secondArtistId),
            tag.values["musicbrainz artist id"],
        )
    }

    @Test
    fun id3v22AliasesExposeTxxTyeTcoAndUfi() {
        val recordingId = "abcdefab-cdef-abcd-efab-cdefabcdefab"
        val releaseId = "01234567-89ab-cdef-0123-456789abcdef"
        val body = v22Frame("TXX", userTextPayload("MusicBrainz Album Id", releaseId)) +
            v22Frame("TYE", textPayload("2007")) +
            v22Frame("TCO", textPayload("Rock")) +
            v22Frame(
                "UFI",
                "http://musicbrainz.org".encodeToByteArray() +
                    byteArrayOf(0) +
                    recordingId.encodeToByteArray(),
            )

        val tag = assertNotNull(
            parseId3Tag(
                header = id3Header(version = 2, flags = 0, storedBodySize = body.size),
                body = body,
            )
        )
        val metadata = tag.toEmbeddedMetadata("fingerprint")

        assertTrue(tag.issues.isEmpty())
        assertEquals(recordingId, metadata.recordingMbid)
        assertEquals(releaseId, metadata.releaseMbid)
        assertEquals(2007, metadata.year)
        assertEquals(listOf("rock"), metadata.genres)
    }

    @Test
    fun unsupportedCompressedFrameIsSkippedWithoutHidingFollowingFrames() {
        val releaseId = "fedcba98-7654-3210-fedc-ba9876543210"
        val body = v23Frame(
            id = "TXXX",
            payload = byteArrayOf(1, 2, 3),
            formatFlags = 0x80,
        ) + v23Frame(
            id = "TXXX",
            payload = userTextPayload("MusicBrainz Album Id", releaseId),
        )

        val tag = assertNotNull(
            parseId3Tag(
                header = id3Header(version = 3, flags = 0, storedBodySize = body.size),
                body = body,
            )
        )

        assertTrue(Id3ParseIssue.UNSUPPORTED_FRAME_COMPRESSION in tag.issues)
        assertEquals(releaseId, tag.toEmbeddedMetadata("fingerprint").releaseMbid)
    }

    private fun id3Header(version: Int, flags: Int, storedBodySize: Int): ByteArray {
        return byteArrayOf(
            'I'.code.toByte(),
            'D'.code.toByte(),
            '3'.code.toByte(),
            version.toByte(),
            0,
            flags.toByte(),
        ) + synchsafe(storedBodySize)
    }

    private fun v22Frame(id: String, payload: ByteArray): ByteArray {
        return id.encodeToByteArray() + bigEndian(payload.size, 3) + payload
    }

    private fun v23Frame(
        id: String,
        payload: ByteArray,
        formatFlags: Int = 0,
    ): ByteArray {
        return id.encodeToByteArray() +
            bigEndian(payload.size, 4) +
            byteArrayOf(0, formatFlags.toByte()) +
            payload
    }

    private fun v24Frame(
        id: String,
        payload: ByteArray,
        formatFlags: Int,
    ): ByteArray {
        return id.encodeToByteArray() +
            synchsafe(payload.size) +
            byteArrayOf(0, formatFlags.toByte()) +
            payload
    }

    private fun textPayload(value: String): ByteArray {
        return byteArrayOf(3) + value.encodeToByteArray()
    }

    private fun userTextPayload(description: String, value: String): ByteArray {
        return byteArrayOf(3) +
            description.encodeToByteArray() +
            byteArrayOf(0) +
            value.encodeToByteArray()
    }

    private fun picturePayload(image: ByteArray): ByteArray {
        return byteArrayOf(0) +
            "image/jpeg".encodeToByteArray() +
            byteArrayOf(0, 3, 0) +
            image
    }

    private fun synchsafe(value: Int): ByteArray {
        return byteArrayOf(
            ((value shr 21) and 0x7F).toByte(),
            ((value shr 14) and 0x7F).toByte(),
            ((value shr 7) and 0x7F).toByte(),
            (value and 0x7F).toByte(),
        )
    }

    private fun bigEndian(value: Int, size: Int): ByteArray {
        return ByteArray(size) { index ->
            ((value shr ((size - index - 1) * 8)) and 0xFF).toByte()
        }
    }

    private fun ByteArray.unsynchronise(): ByteArray {
        val result = mutableListOf<Byte>()
        forEachIndexed { index, byte ->
            result += byte
            if (byte.toInt() and 0xFF == 0xFF) {
                val next = getOrNull(index + 1)?.toInt()?.and(0xFF)
                if (next == null || next == 0 || next >= 0xE0) result += 0
            }
        }
        return result.toByteArray()
    }
}

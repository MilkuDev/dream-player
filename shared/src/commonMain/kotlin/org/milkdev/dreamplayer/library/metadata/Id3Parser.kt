@file:Suppress("SpellCheckingInspection")

package org.milkdev.dreamplayer.library.metadata

internal data class Id3Tag(
    val values: Map<String, List<String>>,
    val pictures: List<Id3Picture>,
    val issues: Set<Id3ParseIssue>,
) {
    fun preferredPicture(): Id3Picture? {
        return pictures.firstOrNull { it.pictureType == FRONT_COVER_PICTURE_TYPE }
            ?: pictures.firstOrNull()
    }
}

internal data class Id3Picture(
    val bytes: ByteArray,
    val mimeType: String,
    val pictureType: Int,
)

internal enum class Id3ParseIssue {
    UNSUPPORTED_TAG_COMPRESSION,
    MALFORMED_EXTENDED_HEADER,
    INVALID_FRAME_HEADER,
    TRUNCATED_FRAME,
    UNSUPPORTED_FRAME_COMPRESSION,
    UNSUPPORTED_FRAME_ENCRYPTION,
    MALFORMED_FRAME_PAYLOAD,
}

/**
 * Parses an ID3v2 body using its original ten-byte header.
 *
 * ID3v2.2/v2.3 tag-level unsynchronisation is removed before frame boundaries
 * are evaluated because those versions store frame sizes before inserting the
 * unsynchronisation bytes. ID3v2.4 is decoded per frame after its stored size
 * has been used to find the frame boundary.
 */
internal fun parseId3Tag(
    header: ByteArray,
    body: ByteArray,
    maxPictureBytes: Int = DEFAULT_MAX_PICTURE_BYTES,
): Id3Tag? {
    if (!header.isId3Header()) return null

    val version = header[ID3_VERSION_OFFSET].unsigned()
    if (version !in SUPPORTED_ID3_VERSIONS) return null

    val declaredTagSize = header.synchsafeInt(ID3_SIZE_OFFSET)
    if (declaredTagSize <= 0 || declaredTagSize > body.size) return null

    val headerFlags = header[ID3_FLAGS_OFFSET].unsigned()
    val issues = linkedSetOf<Id3ParseIssue>()
    if (version == ID3_V2_2 && headerFlags and ID3_V2_2_COMPRESSION_FLAG != 0) {
        return Id3Tag(
            values = emptyMap(),
            pictures = emptyList(),
            issues = setOf(Id3ParseIssue.UNSUPPORTED_TAG_COMPRESSION),
        )
    }

    val storedBodyEnd = if (
        version == ID3_V2_4 &&
        headerFlags and ID3_V2_4_FOOTER_FLAG != 0 &&
        declaredTagSize >= ID3_FOOTER_SIZE
    ) {
        declaredTagSize - ID3_FOOTER_SIZE
    } else {
        declaredTagSize
    }
    var frameBytes = body.copyOfRange(0, storedBodyEnd)

    val tagUnsynchronised = headerFlags and ID3_UNSYNCHRONISATION_FLAG != 0
    if (tagUnsynchronised && version < ID3_V2_4) {
        frameBytes = frameBytes.removeUnsynchronisation()
    }

    val firstFrameOffset: Int = frameBytes.extendedHeaderEnd(version, headerFlags)
        ?: return Id3Tag(
            values = emptyMap(),
            pictures = emptyList(),
            issues = issues + Id3ParseIssue.MALFORMED_EXTENDED_HEADER,
        )

    val mutableValues = linkedMapOf<String, MutableList<String>>()
    val pictures = mutableListOf<Id3Picture>()
    var offset = firstFrameOffset

    while (offset < frameBytes.size) {
        val frameHeaderSize = if (version == ID3_V2_2) {
            ID3_V2_2_FRAME_HEADER_SIZE
        } else {
            ID3_V2_3_FRAME_HEADER_SIZE
        }
        if (offset + frameHeaderSize > frameBytes.size) {
            if (!frameBytes.isPaddingFrom(offset)) issues += Id3ParseIssue.TRUNCATED_FRAME
            break
        }
        if (frameBytes[offset] == 0.toByte()) break

        val frameIdLength = if (version == ID3_V2_2) {
            ID3_V2_2_FRAME_ID_SIZE
        } else {
            ID3_V2_3_FRAME_ID_SIZE
        }
        val rawFrameId = frameBytes.ascii(offset, offset + frameIdLength)
        if (!rawFrameId.isValidFrameId()) {
            issues += Id3ParseIssue.INVALID_FRAME_HEADER
            break
        }
        val frameId = if (version == ID3_V2_2) {
            ID3_V2_2_FRAME_ALIASES[rawFrameId] ?: rawFrameId
        } else {
            rawFrameId
        }

        val frameSize = when (version) {
            ID3_V2_2 -> frameBytes.bigEndianInt(offset + ID3_V2_2_FRAME_ID_SIZE, 3)
            ID3_V2_4 -> frameBytes.synchsafeInt(offset + ID3_V2_3_FRAME_ID_SIZE)
            else -> frameBytes.bigEndianInt(offset + ID3_V2_3_FRAME_ID_SIZE, 4)
        }
        if (frameSize <= 0) {
            issues += Id3ParseIssue.INVALID_FRAME_HEADER
            break
        }

        val payloadStart = offset + frameHeaderSize
        val payloadEnd = payloadStart.toLong() + frameSize
        if (payloadEnd > frameBytes.size) {
            issues += Id3ParseIssue.TRUNCATED_FRAME
            break
        }

        var payload = frameBytes.copyOfRange(payloadStart, payloadEnd.toInt())
        val formatResult = payload.applyFrameFormat(
            version = version,
            firstFlagsByte = if (version == ID3_V2_2) 0 else {
                frameBytes[offset + ID3_V2_3_FRAME_FLAGS_OFFSET].unsigned()
            },
            secondFlagsByte = if (version == ID3_V2_2) 0 else {
                frameBytes[offset + ID3_V2_3_FRAME_FLAGS_OFFSET + 1].unsigned()
            },
            tagUnsynchronised = tagUnsynchronised,
        )
        issues += formatResult.issues
        payload = formatResult.payload

        if (!formatResult.skipFrame) {
            when {
                frameId == USER_TEXT_FRAME_ID -> {
                    val userText = payload.parseUserTextFrame()
                    if (userText == null) {
                        issues += Id3ParseIssue.MALFORMED_FRAME_PAYLOAD
                    } else {
                        userText.values.forEach { value ->
                            mutableValues.addValue(userText.description, value)
                            mutableValues.addValue(
                                "$USER_TEXT_KEY_PREFIX${userText.description}",
                                value,
                            )
                        }
                    }
                }

                frameId == UNIQUE_FILE_ID_FRAME_ID -> {
                    val uniqueFileId = payload.parseUniqueFileIdentifier()
                    if (uniqueFileId == null) {
                        issues += Id3ParseIssue.MALFORMED_FRAME_PAYLOAD
                    } else {
                        mutableValues.addValue(UNIQUE_FILE_ID_KEY, uniqueFileId.identifier)
                        mutableValues.addValue(
                            "$UNIQUE_FILE_ID_KEY_PREFIX${uniqueFileId.owner}",
                            uniqueFileId.identifier,
                        )
                    }
                }

                frameId == ATTACHED_PICTURE_FRAME_ID || frameId == ID3_V2_2_PICTURE_FRAME_ID -> {
                    val picture = payload.parsePictureFrame(
                        isId3V2TwoPicture = rawFrameId == ID3_V2_2_PICTURE_FRAME_ID,
                        maxPictureBytes = maxPictureBytes,
                    )
                    if (picture == null) {
                        issues += Id3ParseIssue.MALFORMED_FRAME_PAYLOAD
                    } else {
                        pictures += picture
                    }
                }

                frameId.startsWith(TEXT_FRAME_PREFIX) -> {
                    val values = payload.parseTextFrame()
                    if (values.isEmpty()) {
                        issues += Id3ParseIssue.MALFORMED_FRAME_PAYLOAD
                    } else {
                        values.forEach { value -> mutableValues.addValue(frameId, value) }
                    }
                }
            }
        }

        offset = payloadEnd.toInt()
    }

    return Id3Tag(
        values = mutableValues.mapValues { (_, values) -> values.toList() },
        pictures = pictures.toList(),
        issues = issues.toSet(),
    )
}

internal fun Id3Tag.toEmbeddedMetadata(fingerprint: String): EmbeddedMetadata {
    return values.toEmbeddedMetadata(fingerprint)
}

internal fun Map<String, List<String>>.toEmbeddedMetadata(fingerprint: String): EmbeddedMetadata {
    return EmbeddedMetadata(
        recordingMbid = firstMusicBrainzId(
            valueFor(
                "musicbrainz_trackid",
                "musicbrainz track id",
                "txxx:musicbrainz_trackid",
                "txxx:musicbrainz track id",
                "ufid:http://musicbrainz.org",
                "ufid",
            )
        ),
        releaseMbid = firstMusicBrainzId(
            valueFor(
                "musicbrainz_albumid",
                "musicbrainz album id",
                "txxx:musicbrainz_albumid",
                "txxx:musicbrainz album id",
            )
        ),
        releaseGroupMbid = firstMusicBrainzId(
            valueFor(
                "musicbrainz_releasegroupid",
                "musicbrainz release group id",
                "txxx:musicbrainz_releasegroupid",
                "txxx:musicbrainz release group id",
            )
        ),
        artistMbids = splitMusicBrainzIds(
            valueFor(
                "musicbrainz_artistid",
                "musicbrainz artist id",
                "txxx:musicbrainz_artistid",
                "txxx:musicbrainz artist id",
            )
        ),
        albumArtistMbids = splitMusicBrainzIds(
            valueFor(
                "musicbrainz_albumartistid",
                "musicbrainz album artist id",
                "txxx:musicbrainz_albumartistid",
                "txxx:musicbrainz album artist id",
            )
        ),
        year = parseEmbeddedYear(valueFor("date", "year", "tdrc", "tyer")),
        genres = splitEmbeddedGenres(valueFor("genre", "tcon")),
        tagFingerprint = fingerprint,
    )
}

private fun Map<String, List<String>>.valueFor(vararg keys: String): String? {
    return keys.asSequence()
        .flatMap { key -> this[key.lowercase()].orEmpty().asSequence() }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .toList()
        .takeIf(List<String>::isNotEmpty)
        ?.joinToString(";")
}

private data class FormattedFramePayload(
    val payload: ByteArray,
    val skipFrame: Boolean,
    val issues: Set<Id3ParseIssue>,
)

private data class Id3UserText(
    val description: String,
    val values: List<String>,
)

private data class Id3UniqueFileId(
    val owner: String,
    val identifier: String,
)

private fun ByteArray.applyFrameFormat(
    version: Int,
    firstFlagsByte: Int,
    secondFlagsByte: Int,
    tagUnsynchronised: Boolean,
): FormattedFramePayload {
    if (version == ID3_V2_2) {
        return FormattedFramePayload(this, skipFrame = false, issues = emptySet())
    }

    val issues = linkedSetOf<Id3ParseIssue>()
    val compressed = when (version) {
        ID3_V2_3 -> secondFlagsByte and ID3_V2_3_FRAME_COMPRESSION_FLAG != 0
        else -> secondFlagsByte and ID3_V2_4_FRAME_COMPRESSION_FLAG != 0
    }
    val encrypted = when (version) {
        ID3_V2_3 -> secondFlagsByte and ID3_V2_3_FRAME_ENCRYPTION_FLAG != 0
        else -> secondFlagsByte and ID3_V2_4_FRAME_ENCRYPTION_FLAG != 0
    }
    if (compressed) issues += Id3ParseIssue.UNSUPPORTED_FRAME_COMPRESSION
    if (encrypted) issues += Id3ParseIssue.UNSUPPORTED_FRAME_ENCRYPTION
    if (compressed || encrypted) {
        return FormattedFramePayload(this, skipFrame = true, issues = issues)
    }

    var offset = 0
    val grouped = when (version) {
        ID3_V2_3 -> secondFlagsByte and ID3_V2_3_FRAME_GROUPING_FLAG != 0
        else -> secondFlagsByte and ID3_V2_4_FRAME_GROUPING_FLAG != 0
    }
    if (grouped) offset += 1

    if (
        version == ID3_V2_4 &&
        secondFlagsByte and ID3_V2_4_FRAME_DATA_LENGTH_INDICATOR_FLAG != 0
    ) {
        offset += SYNCHSAFE_INTEGER_BYTES
    }
    if (offset > size) {
        return FormattedFramePayload(
            payload = byteArrayOf(),
            skipFrame = true,
            issues = issues + Id3ParseIssue.MALFORMED_FRAME_PAYLOAD,
        )
    }

    var result = copyOfRange(offset, size)
    if (
        version == ID3_V2_4 &&
        (
            tagUnsynchronised ||
                secondFlagsByte and ID3_V2_4_FRAME_UNSYNCHRONISATION_FLAG != 0
            )
    ) {
        result = result.removeUnsynchronisation()
    }

    // Accessing this argument documents that the status byte is intentionally
    // ignored: alter-preservation and read-only flags do not change decoding.
    @Suppress("UNUSED_VARIABLE")
    val ignoredStatusFlags = firstFlagsByte
    return FormattedFramePayload(result, skipFrame = false, issues = issues)
}

private fun ByteArray.parseUserTextFrame(): Id3UserText? {
    if (isEmpty()) return null
    val encoding = this[0].unsigned()
    if (encoding !in SUPPORTED_TEXT_ENCODINGS) return null

    val descriptionEnd = encodedTextEnd(1, encoding) ?: return null
    val description = decodeId3Text(1, descriptionEnd, encoding).trim().lowercase()
    val valueStart = descriptionEnd + encodedTerminatorSize(encoding)
    if (description.isBlank() || valueStart > size) return null

    val values = decodeId3Text(valueStart, size, encoding)
        .split(ID3_TEXT_VALUE_SEPARATOR)
        .map(String::trim)
        .filter(String::isNotBlank)
    return values.takeIf(List<String>::isNotEmpty)?.let { Id3UserText(description, it) }
}

private fun ByteArray.parseTextFrame(): List<String> {
    if (isEmpty()) return emptyList()
    val encoding = this[0].unsigned()
    if (encoding !in SUPPORTED_TEXT_ENCODINGS) return emptyList()

    return decodeId3Text(1, size, encoding)
        .split(ID3_TEXT_VALUE_SEPARATOR)
        .map(String::trim)
        .filter(String::isNotBlank)
}

private fun ByteArray.parseUniqueFileIdentifier(): Id3UniqueFileId? {
    val ownerEnd = indexOfZero(0)
    if (ownerEnd <= 0 || ownerEnd + 1 >= size) return null

    val owner = latin1(0, ownerEnd).trim().lowercase()
    val identifier = latin1(ownerEnd + 1, size).trim().trim(ID3_TEXT_VALUE_SEPARATOR)
    if (owner.isBlank() || identifier.isBlank()) return null
    return Id3UniqueFileId(owner, identifier)
}

private fun ByteArray.parsePictureFrame(
    isId3V2TwoPicture: Boolean,
    maxPictureBytes: Int,
): Id3Picture? {
    if (isEmpty()) return null

    val encoding = this[0].unsigned()
    if (encoding !in SUPPORTED_TEXT_ENCODINGS) return null
    var offset = 1
    val mimeType = if (isId3V2TwoPicture) {
        if (offset + ID3_V2_2_PICTURE_FORMAT_SIZE > size) return null
        ascii(offset, offset + ID3_V2_2_PICTURE_FORMAT_SIZE)
            .toPictureMimeType()
            .also { offset += ID3_V2_2_PICTURE_FORMAT_SIZE }
    } else {
        val mimeEnd = indexOfZero(offset)
        if (mimeEnd < 0) return null
        latin1(offset, mimeEnd).trim().lowercase().also { offset = mimeEnd + 1 }
    }

    if (offset >= size) return null
    val pictureType = this[offset++].unsigned()
    val descriptionEnd = encodedTextEnd(offset, encoding) ?: return null
    offset = descriptionEnd + encodedTerminatorSize(encoding)
    if (offset > size) return null

    val imageBytes = copyOfRange(offset, size)
    if (imageBytes.isEmpty() || imageBytes.size > maxPictureBytes) return null
    return Id3Picture(
        bytes = imageBytes,
        mimeType = mimeType,
        pictureType = pictureType,
    )
}

private fun ByteArray.extendedHeaderEnd(version: Int, headerFlags: Int): Int? {
    if (headerFlags and ID3_EXTENDED_HEADER_FLAG == 0) return 0
    if (version == ID3_V2_2 || size < SYNCHSAFE_INTEGER_BYTES) return null

    val end = when (version) {
        ID3_V2_3 -> {
            val payloadSize = bigEndianInt(0, INT_BYTES)
            if (payloadSize < ID3_V2_3_MIN_EXTENDED_HEADER_PAYLOAD_SIZE) return null
            payloadSize.toLong() + INT_BYTES
        }

        else -> {
            val totalSize = synchsafeInt(0)
            if (totalSize < ID3_V2_4_MIN_EXTENDED_HEADER_SIZE) return null
            totalSize.toLong()
        }
    }
    return end.takeIf { it <= size }?.toInt()
}

private fun ByteArray.removeUnsynchronisation(): ByteArray {
    if (size < 2) return this

    val result = ByteArray(size)
    var readIndex = 0
    var writeIndex = 0
    while (readIndex < size) {
        val current = this[readIndex]
        result[writeIndex++] = current
        if (
            current.unsigned() == UNSYNCHRONISATION_MARKER &&
            readIndex + 1 < size &&
            this[readIndex + 1] == 0.toByte()
        ) {
            val following = this.getOrNull(readIndex + 2)?.unsigned()
            if (following == null || following == 0 || following >= MPEG_SYNC_MINIMUM) {
                readIndex += 1
            }
        }
        readIndex += 1
    }
    return result.copyOf(writeIndex)
}

private fun ByteArray.decodeId3Text(start: Int, end: Int, encoding: Int): String {
    if (start < 0 || end <= start || end > size) return ""
    return when (encoding) {
        ID3_TEXT_ENCODING_ISO_8859_1 -> latin1(start, end)
        ID3_TEXT_ENCODING_UTF_16_WITH_BOM -> utf16(start, end, bomRequired = false)
        ID3_TEXT_ENCODING_UTF_16_BE -> utf16(start, end, bomRequired = false, defaultBigEndian = true)
        ID3_TEXT_ENCODING_UTF_8 -> copyOfRange(start, end).decodeToString(throwOnInvalidSequence = false)
        else -> ""
    }
}

private fun ByteArray.utf16(
    start: Int,
    end: Int,
    bomRequired: Boolean,
    defaultBigEndian: Boolean = true,
): String {
    var offset = start
    var bigEndian = defaultBigEndian
    if (offset + 1 < end) {
        val first = this[offset].unsigned()
        val second = this[offset + 1].unsigned()
        when {
            first == UTF_16_BE_BOM_FIRST && second == UTF_16_BE_BOM_SECOND -> {
                bigEndian = true
                offset += 2
            }

            first == UTF_16_LE_BOM_FIRST && second == UTF_16_LE_BOM_SECOND -> {
                bigEndian = false
                offset += 2
            }

            bomRequired -> return ""
        }
    } else if (bomRequired) {
        return ""
    }

    return buildString {
        while (offset + 1 < end) {
            val first = this@utf16[offset].unsigned()
            val second = this@utf16[offset + 1].unsigned()
            val codeUnit = if (bigEndian) {
                (first shl BITS_PER_BYTE) or second
            } else {
                (second shl BITS_PER_BYTE) or first
            }
            append(codeUnit.toChar())
            offset += 2
        }
    }
}

private fun ByteArray.encodedTextEnd(startIndex: Int, encoding: Int): Int? {
    if (startIndex !in 0..size) return null
    if (encoding != ID3_TEXT_ENCODING_UTF_16_WITH_BOM && encoding != ID3_TEXT_ENCODING_UTF_16_BE) {
        return indexOfZero(startIndex).takeIf { it >= 0 }
    }

    var index = startIndex
    while (index + 1 < size) {
        if (this[index] == 0.toByte() && this[index + 1] == 0.toByte()) return index
        index += 2
    }
    return null
}

private fun encodedTerminatorSize(encoding: Int): Int {
    return if (
        encoding == ID3_TEXT_ENCODING_UTF_16_WITH_BOM ||
        encoding == ID3_TEXT_ENCODING_UTF_16_BE
    ) {
        2
    } else {
        1
    }
}

private fun MutableMap<String, MutableList<String>>.addValue(key: String, value: String) {
    val normalizedKey = key.trim().lowercase()
    val normalizedValue = value.trim()
    if (normalizedKey.isBlank() || normalizedValue.isBlank()) return
    getOrPut(normalizedKey) { mutableListOf() }.add(normalizedValue)
}

private fun String.isValidFrameId(): Boolean {
    return isNotEmpty() && all { character ->
        character in 'A'..'Z' || character in '0'..'9'
    }
}

private fun ByteArray.isId3Header(): Boolean {
    return size >= ID3_HEADER_SIZE &&
        this[0] == 'I'.code.toByte() &&
        this[1] == 'D'.code.toByte() &&
        this[2] == '3'.code.toByte()
}

private fun ByteArray.isPaddingFrom(startIndex: Int): Boolean {
    return (startIndex until size).all { index -> this[index] == 0.toByte() }
}

private fun ByteArray.ascii(start: Int, end: Int): String {
    return latin1(start, end)
}

private fun ByteArray.latin1(start: Int, end: Int): String {
    if (start < 0 || end <= start || end > size) return ""
    return buildString(end - start) {
        for (index in start until end) append(this@latin1[index].unsigned().toChar())
    }
}

private fun ByteArray.indexOfZero(startIndex: Int): Int {
    if (startIndex !in 0..size) return -1
    return (startIndex until size).firstOrNull { index -> this[index] == 0.toByte() } ?: -1
}

private fun ByteArray.synchsafeInt(startIndex: Int): Int {
    if (startIndex < 0 || startIndex + SYNCHSAFE_INTEGER_BYTES > size) return -1
    if ((startIndex until startIndex + SYNCHSAFE_INTEGER_BYTES).any { this[it].unsigned() > SYNCHSAFE_BYTE_MASK }) {
        return -1
    }
    return (startIndex until startIndex + SYNCHSAFE_INTEGER_BYTES).fold(0) { value, index ->
        (value shl SYNCHSAFE_BITS_PER_BYTE) or this[index].unsigned()
    }
}

private fun ByteArray.bigEndianInt(startIndex: Int, byteCount: Int): Int {
    if (startIndex < 0 || byteCount <= 0 || startIndex + byteCount > size) return -1
    return (startIndex until startIndex + byteCount).fold(0) { value, index ->
        (value shl BITS_PER_BYTE) or this[index].unsigned()
    }
}

private fun String.toPictureMimeType(): String {
    return when (trim().uppercase()) {
        "JPG", "JPEG" -> "image/jpeg"
        "PNG" -> "image/png"
        "GIF" -> "image/gif"
        else -> "application/octet-stream"
    }
}

private fun Byte.unsigned(): Int = toInt() and UNSIGNED_BYTE_MASK

private const val ID3_HEADER_SIZE = 10
private const val ID3_VERSION_OFFSET = 3
private const val ID3_FLAGS_OFFSET = 5
private const val ID3_SIZE_OFFSET = 6
private const val ID3_FOOTER_SIZE = 10
private const val ID3_V2_2 = 2
private const val ID3_V2_3 = 3
private const val ID3_V2_4 = 4
private const val ID3_UNSYNCHRONISATION_FLAG = 0x80
private const val ID3_EXTENDED_HEADER_FLAG = 0x40
private const val ID3_V2_2_COMPRESSION_FLAG = 0x40
private const val ID3_V2_4_FOOTER_FLAG = 0x10
private const val ID3_V2_2_FRAME_HEADER_SIZE = 6
private const val ID3_V2_3_FRAME_HEADER_SIZE = 10
private const val ID3_V2_2_FRAME_ID_SIZE = 3
private const val ID3_V2_3_FRAME_ID_SIZE = 4
private const val ID3_V2_3_FRAME_FLAGS_OFFSET = 8
private const val ID3_V2_3_MIN_EXTENDED_HEADER_PAYLOAD_SIZE = 6
private const val ID3_V2_4_MIN_EXTENDED_HEADER_SIZE = 6
private const val ID3_V2_3_FRAME_COMPRESSION_FLAG = 0x80
private const val ID3_V2_3_FRAME_ENCRYPTION_FLAG = 0x40
private const val ID3_V2_3_FRAME_GROUPING_FLAG = 0x20
private const val ID3_V2_4_FRAME_GROUPING_FLAG = 0x40
private const val ID3_V2_4_FRAME_COMPRESSION_FLAG = 0x08
private const val ID3_V2_4_FRAME_ENCRYPTION_FLAG = 0x04
private const val ID3_V2_4_FRAME_UNSYNCHRONISATION_FLAG = 0x02
private const val ID3_V2_4_FRAME_DATA_LENGTH_INDICATOR_FLAG = 0x01
private const val ID3_TEXT_ENCODING_ISO_8859_1 = 0
private const val ID3_TEXT_ENCODING_UTF_16_WITH_BOM = 1
private const val ID3_TEXT_ENCODING_UTF_16_BE = 2
private const val ID3_TEXT_ENCODING_UTF_8 = 3
private const val ID3_V2_2_PICTURE_FORMAT_SIZE = 3
private const val FRONT_COVER_PICTURE_TYPE = 3
private const val SYNCHSAFE_INTEGER_BYTES = 4
private const val SYNCHSAFE_BITS_PER_BYTE = 7
private const val SYNCHSAFE_BYTE_MASK = 0x7F
private const val BITS_PER_BYTE = 8
private const val INT_BYTES = 4
private const val UNSIGNED_BYTE_MASK = 0xFF
private const val UNSYNCHRONISATION_MARKER = 0xFF
private const val MPEG_SYNC_MINIMUM = 0xE0
private const val UTF_16_BE_BOM_FIRST = 0xFE
private const val UTF_16_BE_BOM_SECOND = 0xFF
private const val UTF_16_LE_BOM_FIRST = 0xFF
private const val UTF_16_LE_BOM_SECOND = 0xFE
private const val ID3_TEXT_VALUE_SEPARATOR = '\u0000'
private const val TEXT_FRAME_PREFIX = "T"
private const val USER_TEXT_FRAME_ID = "TXXX"
private const val USER_TEXT_KEY_PREFIX = "txxx:"
private const val UNIQUE_FILE_ID_FRAME_ID = "UFID"
private const val UNIQUE_FILE_ID_KEY = "ufid"
private const val UNIQUE_FILE_ID_KEY_PREFIX = "ufid:"
private const val ATTACHED_PICTURE_FRAME_ID = "APIC"
private const val ID3_V2_2_PICTURE_FRAME_ID = "PIC"
private const val DEFAULT_MAX_PICTURE_BYTES = 16 * 1024 * 1024
private val SUPPORTED_ID3_VERSIONS = ID3_V2_2..ID3_V2_4
private val SUPPORTED_TEXT_ENCODINGS = ID3_TEXT_ENCODING_ISO_8859_1..ID3_TEXT_ENCODING_UTF_8
private val ID3_V2_2_FRAME_ALIASES = mapOf(
    "BUF" to "RBUF",
    "CNT" to "PCNT",
    "COM" to "COMM",
    "CRA" to "AENC",
    "ETC" to "ETCO",
    "EQU" to "EQUA",
    "GEO" to "GEOB",
    "IPL" to "IPLS",
    "LNK" to "LINK",
    "MCI" to "MCDI",
    "MLL" to "MLLT",
    "PIC" to "APIC",
    "POP" to "POPM",
    "REV" to "RVRB",
    "RVA" to "RVAD",
    "SLT" to "SYLT",
    "STC" to "SYTC",
    "TAL" to "TALB",
    "TBP" to "TBPM",
    "TCM" to "TCOM",
    "TCO" to "TCON",
    "TCR" to "TCOP",
    "TDA" to "TDAT",
    "TDY" to "TDLY",
    "TEN" to "TENC",
    "TFT" to "TFLT",
    "TIM" to "TIME",
    "TKE" to "TKEY",
    "TLA" to "TLAN",
    "TLE" to "TLEN",
    "TMT" to "TMED",
    "TOA" to "TOPE",
    "TOF" to "TOFN",
    "TOL" to "TOLY",
    "TOR" to "TORY",
    "TOT" to "TOAL",
    "TP1" to "TPE1",
    "TP2" to "TPE2",
    "TP3" to "TPE3",
    "TP4" to "TPE4",
    "TPA" to "TPOS",
    "TPB" to "TPUB",
    "TRC" to "TSRC",
    "TRD" to "TRDA",
    "TRK" to "TRCK",
    "TSI" to "TSIZ",
    "TSS" to "TSSE",
    "TT1" to "TIT1",
    "TT2" to "TIT2",
    "TT3" to "TIT3",
    "TXT" to "TEXT",
    "TXX" to "TXXX",
    "TYE" to "TYER",
    "UFI" to "UFID",
    "ULT" to "USLT",
)

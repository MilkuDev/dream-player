@file:Suppress("SpellCheckingInspection")

package org.milkdev.dreamplayer.library.metadata

internal fun interface Mp4RandomAccessReader {
    fun read(offset: Long, byteCount: Int): ByteArray?
}

internal data class Mp4Tag(
    val values: Map<String, List<String>>,
    val fingerprintMaterial: ByteArray,
    val issues: Set<Mp4ParseIssue>,
) {
    fun toEmbeddedMetadata(fingerprint: String): EmbeddedMetadata {
        return values.toEmbeddedMetadata(fingerprint)
    }
}

internal enum class Mp4ParseIssue {
    MALFORMED_ATOM,
    ATOM_LIMIT_REACHED,
    NESTING_LIMIT_REACHED,
    VALUE_TOO_LARGE,
    MALFORMED_FREEFORM_TAG,
    UNSUPPORTED_DATA_TYPE,
}

/**
 * Parses iTunes-style metadata without loading the MP4/M4A file into memory.
 *
 * Only atom headers and bounded metadata values are requested from [reader].
 * Large `mdat` atoms are skipped by their declared size, so a `moov` atom at
 * the end of a multi-gigabyte file remains cheap to reach.
 */
internal fun parseMp4Tag(
    fileSize: Long,
    reader: Mp4RandomAccessReader,
): Mp4Tag? {
    if (fileSize < MP4_ATOM_HEADER_SIZE) return null
    return Mp4Parser(fileSize, reader).parse()
}

private class Mp4Parser(
    private val fileSize: Long,
    private val reader: Mp4RandomAccessReader,
) {
    private val values = linkedMapOf<String, MutableList<String>>()
    private val issues = linkedSetOf<Mp4ParseIssue>()
    private var visitedAtoms = 0
    private var storedValues = 0
    private var sawFileType = false
    private var sawMovie = false

    fun parse(): Mp4Tag? {
        var offset = 0L
        while (offset < fileSize) {
            val atom = readAtom(offset, fileSize) ?: break
            when (atom.type) {
                FILE_TYPE_ATOM -> sawFileType = true
                MOVIE_ATOM -> {
                    sawMovie = true
                    scanContainer(atom.contentStart, atom.end, depth = 1)
                }
            }
            if (atom.end <= offset) {
                issues += Mp4ParseIssue.MALFORMED_ATOM
                break
            }
            offset = atom.end
        }

        if (!sawFileType || !sawMovie) return null
        val immutableValues = values.mapValues { (_, entries) -> entries.toList() }
        return Mp4Tag(
            values = immutableValues,
            fingerprintMaterial = immutableValues.fingerprintMaterial(),
            issues = issues.toSet(),
        )
    }

    private fun scanContainer(start: Long, end: Long, depth: Int) {
        if (depth > MAX_MP4_NESTING_DEPTH) {
            issues += Mp4ParseIssue.NESTING_LIMIT_REACHED
            return
        }

        var offset = start
        while (offset < end) {
            val atom = readAtom(offset, end) ?: break
            when (atom.type) {
                USER_DATA_ATOM -> scanContainer(atom.contentStart, atom.end, depth + 1)
                META_ATOM -> scanMetaAtom(atom, depth + 1)
                ITEM_LIST_ATOM -> parseItemList(atom, depth + 1)
            }
            if (atom.end <= offset) {
                issues += Mp4ParseIssue.MALFORMED_ATOM
                break
            }
            offset = atom.end
        }
    }

    private fun scanMetaAtom(atom: Mp4Atom, depth: Int) {
        val directChild = readAtom(atom.contentStart, atom.end)
        val childrenStart = if (directChild?.type in META_CHILD_ATOMS) {
            atom.contentStart
        } else {
            atom.contentStart + MP4_FULL_BOX_HEADER_SIZE
        }
        if (childrenStart > atom.end) {
            issues += Mp4ParseIssue.MALFORMED_ATOM
            return
        }
        scanContainer(childrenStart, atom.end, depth)
    }

    private fun parseItemList(atom: Mp4Atom, depth: Int) {
        if (depth > MAX_MP4_NESTING_DEPTH) {
            issues += Mp4ParseIssue.NESTING_LIMIT_REACHED
            return
        }

        var offset = atom.contentStart
        while (offset < atom.end) {
            val item = readAtom(offset, atom.end) ?: break
            when (item.type) {
                FREEFORM_ITEM_ATOM -> parseFreeformItem(item)
                DATE_ITEM_ATOM -> parseStandardTextItem(item, DATE_METADATA_KEY)
                GENRE_ITEM_ATOM -> parseStandardTextItem(item, GENRE_METADATA_KEY)
            }
            if (item.end <= offset) {
                issues += Mp4ParseIssue.MALFORMED_ATOM
                break
            }
            offset = item.end
        }
    }

    private fun parseFreeformItem(item: Mp4Atom) {
        var offset = item.contentStart
        var mean: String? = null
        var name: String? = null
        val itemValues = mutableListOf<String>()

        while (offset < item.end) {
            val child = readAtom(offset, item.end) ?: break
            when (child.type) {
                MEAN_ATOM -> mean = readFullBoxText(child)
                NAME_ATOM -> name = readFullBoxText(child)
                DATA_ATOM -> readDataValue(child)?.let(itemValues::add)
            }
            if (child.end <= offset) {
                issues += Mp4ParseIssue.MALFORMED_ATOM
                break
            }
            offset = child.end
        }

        if (!mean.orEmpty().trim().equals(ITUNES_FREEFORM_MEAN, ignoreCase = true)) return

        val normalizedName = name?.normalizeMetadataKey()
        if (normalizedName.isNullOrBlank() || itemValues.isEmpty()) {
            issues += Mp4ParseIssue.MALFORMED_FREEFORM_TAG
            return
        }
        itemValues.forEach { value -> addValue(normalizedName, value) }
    }

    private fun parseStandardTextItem(item: Mp4Atom, key: String) {
        var offset = item.contentStart
        while (offset < item.end) {
            val child = readAtom(offset, item.end) ?: break
            if (child.type == DATA_ATOM) {
                readDataValue(child)?.let { value -> addValue(key, value) }
            }
            if (child.end <= offset) {
                issues += Mp4ParseIssue.MALFORMED_ATOM
                break
            }
            offset = child.end
        }
    }

    private fun readFullBoxText(atom: Mp4Atom): String? {
        val contentSize = atom.contentSize
        if (contentSize < MP4_FULL_BOX_HEADER_SIZE || contentSize > MAX_MP4_VALUE_ATOM_SIZE) {
            if (contentSize > MAX_MP4_VALUE_ATOM_SIZE) issues += Mp4ParseIssue.VALUE_TOO_LARGE
            return null
        }
        val content = readExact(atom.contentStart, contentSize.toInt()) ?: return null
        return content.decodeToString(
            startIndex = MP4_FULL_BOX_HEADER_SIZE,
            endIndex = content.size,
            throwOnInvalidSequence = false,
        ).trim().trim('\u0000').takeIf(String::isNotBlank)
    }

    private fun readDataValue(atom: Mp4Atom): String? {
        val contentSize = atom.contentSize
        if (contentSize < MP4_DATA_PREFIX_SIZE || contentSize > MAX_MP4_VALUE_ATOM_SIZE) {
            if (contentSize > MAX_MP4_VALUE_ATOM_SIZE) issues += Mp4ParseIssue.VALUE_TOO_LARGE
            return null
        }

        val content = readExact(atom.contentStart, contentSize.toInt()) ?: return null
        val dataType = content.unsignedInt(0)?.and(MP4_DATA_TYPE_MASK) ?: return null
        val value = when (dataType) {
            MP4_DATA_TYPE_IMPLICIT,
            MP4_DATA_TYPE_UTF_8,
            -> content.decodeToString(
                startIndex = MP4_DATA_PREFIX_SIZE,
                endIndex = content.size,
                throwOnInvalidSequence = false,
            )

            MP4_DATA_TYPE_UTF_16 -> content.decodeUtf16BigEndian(MP4_DATA_PREFIX_SIZE)
            else -> {
                issues += Mp4ParseIssue.UNSUPPORTED_DATA_TYPE
                return null
            }
        }
        return value.trim().trim('\u0000').takeIf(String::isNotBlank)
    }

    private fun addValue(key: String, value: String) {
        if (storedValues >= MAX_MP4_STORED_VALUES) {
            issues += Mp4ParseIssue.ATOM_LIMIT_REACHED
            return
        }
        val normalizedKey = key.normalizeMetadataKey()
        val normalizedValue = value.trim()
        if (normalizedKey.isBlank() || normalizedValue.isBlank()) return

        values.getOrPut(normalizedKey) { mutableListOf() }.add(normalizedValue)
        storedValues += 1
    }

    private fun readAtom(offset: Long, parentEnd: Long): Mp4Atom? {
        if (visitedAtoms >= MAX_MP4_VISITED_ATOMS) {
            issues += Mp4ParseIssue.ATOM_LIMIT_REACHED
            return null
        }
        visitedAtoms += 1

        if (offset < 0L || parentEnd > fileSize || parentEnd - offset < MP4_ATOM_HEADER_SIZE) {
            if (offset != parentEnd) issues += Mp4ParseIssue.MALFORMED_ATOM
            return null
        }

        val baseHeader = readExact(offset, MP4_ATOM_HEADER_SIZE.toInt()) ?: run {
            issues += Mp4ParseIssue.MALFORMED_ATOM
            return null
        }
        val shortSize = baseHeader.unsignedInt(0) ?: return null
        val type = baseHeader.unsignedInt(INT_BYTES) ?: return null
        val headerSize: Long
        val atomSize: Long
        when (shortSize) {
            MP4_EXTENDED_SIZE_MARKER -> {
                val extendedSize = readExact(offset + MP4_ATOM_HEADER_SIZE, LONG_BYTES)
                    ?.unsignedLong(0)
                if (extendedSize == null) {
                    issues += Mp4ParseIssue.MALFORMED_ATOM
                    return null
                }
                headerSize = MP4_EXTENDED_ATOM_HEADER_SIZE
                atomSize = extendedSize
            }

            MP4_TO_PARENT_END_SIZE_MARKER -> {
                headerSize = MP4_ATOM_HEADER_SIZE
                atomSize = parentEnd - offset
            }

            else -> {
                headerSize = MP4_ATOM_HEADER_SIZE
                atomSize = shortSize
            }
        }

        if (atomSize < headerSize || atomSize > parentEnd - offset) {
            issues += Mp4ParseIssue.MALFORMED_ATOM
            return null
        }
        return Mp4Atom(
            type = type,
            contentStart = offset + headerSize,
            end = offset + atomSize,
        )
    }

    private fun readExact(offset: Long, byteCount: Int): ByteArray? {
        if (
            offset < 0L ||
            byteCount < 0 ||
            offset > fileSize ||
            byteCount.toLong() > fileSize - offset
        ) {
            return null
        }
        return reader.read(offset, byteCount)?.takeIf { bytes -> bytes.size == byteCount }
    }
}

private data class Mp4Atom(
    val type: Long,
    val contentStart: Long,
    val end: Long,
) {
    val contentSize: Long
        get() = end - contentStart
}

private fun Map<String, List<String>>.fingerprintMaterial(): ByteArray {
    return buildString {
        if (isEmpty()) append(MP4_EMPTY_FINGERPRINT)
        entries.sortedBy { (key, _) -> key }.forEach { (key, values) ->
            append(key)
            append(FINGERPRINT_KEY_SEPARATOR)
            values.forEach { value ->
                append(value)
                append(FINGERPRINT_VALUE_SEPARATOR)
            }
            append(FINGERPRINT_ENTRY_SEPARATOR)
        }
    }.encodeToByteArray()
}

private fun String.normalizeMetadataKey(): String {
    return trim()
        .lowercase()
        .replace(METADATA_WHITESPACE, " ")
}

private fun ByteArray.decodeUtf16BigEndian(startIndex: Int): String {
    if (startIndex < 0 || startIndex >= size) return ""
    var offset = startIndex
    var bigEndian = true
    if (offset + 1 < size) {
        val first = this[offset].unsigned()
        val second = this[offset + 1].unsigned()
        when {
            first == UTF_16_BE_BOM_FIRST && second == UTF_16_BE_BOM_SECOND -> offset += 2
            first == UTF_16_LE_BOM_FIRST && second == UTF_16_LE_BOM_SECOND -> {
                bigEndian = false
                offset += 2
            }
        }
    }

    return buildString {
        while (offset + 1 < size) {
            val first = this@decodeUtf16BigEndian[offset].unsigned()
            val second = this@decodeUtf16BigEndian[offset + 1].unsigned()
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

private fun ByteArray.unsignedInt(startIndex: Int): Long? {
    if (startIndex < 0 || startIndex + INT_BYTES > size) return null
    return (startIndex until startIndex + INT_BYTES).fold(0L) { value, index ->
        (value shl BITS_PER_BYTE) or this[index].unsigned().toLong()
    }
}

private fun ByteArray.unsignedLong(startIndex: Int): Long? {
    if (startIndex < 0 || startIndex + LONG_BYTES > size) return null
    if (this[startIndex].unsigned() and SIGN_BIT_MASK != 0) return null
    return (startIndex until startIndex + LONG_BYTES).fold(0L) { value, index ->
        (value shl BITS_PER_BYTE) or this[index].unsigned().toLong()
    }
}

private fun Byte.unsigned(): Int = toInt() and UNSIGNED_BYTE_MASK

private const val INT_BYTES = 4
private const val LONG_BYTES = 8
private const val BITS_PER_BYTE = 8
private const val UNSIGNED_BYTE_MASK = 0xFF
private const val SIGN_BIT_MASK = 0x80
private const val MP4_ATOM_HEADER_SIZE = 8L
private const val MP4_EXTENDED_ATOM_HEADER_SIZE = 16L
private const val MP4_FULL_BOX_HEADER_SIZE = 4
private const val MP4_DATA_PREFIX_SIZE = 8
private const val MP4_EXTENDED_SIZE_MARKER = 1L
private const val MP4_TO_PARENT_END_SIZE_MARKER = 0L
private const val MP4_DATA_TYPE_MASK = 0x00FF_FFFFL
private const val MP4_DATA_TYPE_IMPLICIT = 0L
private const val MP4_DATA_TYPE_UTF_8 = 1L
private const val MP4_DATA_TYPE_UTF_16 = 2L
private const val MAX_MP4_VALUE_ATOM_SIZE = 64L * 1024L
private const val MAX_MP4_VISITED_ATOMS = 4096
private const val MAX_MP4_STORED_VALUES = 256
private const val MAX_MP4_NESTING_DEPTH = 8
private const val ITUNES_FREEFORM_MEAN = "com.apple.iTunes"
private const val DATE_METADATA_KEY = "date"
private const val GENRE_METADATA_KEY = "genre"
private const val MP4_EMPTY_FINGERPRINT = "mp4:no-recognized-tags"
private const val FINGERPRINT_KEY_SEPARATOR = '\u001D'
private const val FINGERPRINT_VALUE_SEPARATOR = '\u001E'
private const val FINGERPRINT_ENTRY_SEPARATOR = '\u001F'
private const val UTF_16_BE_BOM_FIRST = 0xFE
private const val UTF_16_BE_BOM_SECOND = 0xFF
private const val UTF_16_LE_BOM_FIRST = 0xFF
private const val UTF_16_LE_BOM_SECOND = 0xFE
private const val FILE_TYPE_ATOM = 0x66747970L // ftyp
private const val MOVIE_ATOM = 0x6D6F6F76L // moov
private const val USER_DATA_ATOM = 0x75647461L // udta
private const val META_ATOM = 0x6D657461L // meta
private const val ITEM_LIST_ATOM = 0x696C7374L // ilst
private const val FREEFORM_ITEM_ATOM = 0x2D2D2D2DL // ----
private const val MEAN_ATOM = 0x6D65616EL // mean
private const val NAME_ATOM = 0x6E616D65L // name
private const val DATA_ATOM = 0x64617461L // data
private const val DATE_ITEM_ATOM = 0xA9646179L // ©day
private const val GENRE_ITEM_ATOM = 0xA967656EL // ©gen
private val META_CHILD_ATOMS = setOf(
    0x68646C72L, // hdlr
    0x6B657973L, // keys
    ITEM_LIST_ATOM,
    0x66726565L, // free
)
private val METADATA_WHITESPACE = Regex("\\s+")

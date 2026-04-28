package dev.jongwoo.androidvm.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal clean-room reader for Android binary XML (AXML / compiled
 * AndroidManifest.xml). Implements only the subset used by Stage 06 launcher
 * activity resolution: the wrapping XML chunk, the string pool (UTF-8 and
 * UTF-16 variants), the resource map (skipped), and start/end element chunks
 * with typed attribute values.
 */
class BinaryAxmlParser(bytes: ByteArray) {

    private val buffer: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private val strings: MutableList<String> = mutableListOf()

    init {
        require(buffer.remaining() >= 8) { "Buffer too small to contain AXML header" }
        val type = buffer.getShort().toInt() and 0xFFFF
        val headerSize = buffer.getShort().toInt() and 0xFFFF
        val totalSize = buffer.getInt()
        require(type == CHUNK_XML) { "Not an AXML file (chunk type=0x${"%04x".format(type)})" }
        require(headerSize == 8) { "Unexpected AXML header size $headerSize" }
        require(totalSize <= bytes.size) { "AXML totalSize $totalSize exceeds buffer ${bytes.size}" }
    }

    fun accept(visitor: Visitor) {
        var stringPoolParsed = false
        while (buffer.remaining() >= 8) {
            val chunkStart = buffer.position()
            val type = buffer.getShort().toInt() and 0xFFFF
            val headerSize = buffer.getShort().toInt() and 0xFFFF
            val chunkSize = buffer.getInt()
            val nextPosition = chunkStart + chunkSize
            when (type) {
                CHUNK_STRING_POOL -> {
                    require(!stringPoolParsed) { "Multiple string pools in AXML" }
                    parseStringPool(chunkStart, headerSize, chunkSize)
                    stringPoolParsed = true
                }
                CHUNK_RESOURCE_MAP -> {
                    // Resource map maps attribute name string indices to resource IDs;
                    // we don't need them to resolve activity launchers by name.
                }
                CHUNK_XML_START_NS,
                CHUNK_XML_END_NS,
                CHUNK_XML_CDATA -> {
                    // Not needed for our visitor surface.
                }
                CHUNK_XML_START_ELEMENT -> parseStartElement(visitor)
                CHUNK_XML_END_ELEMENT -> parseEndElement(visitor)
                else -> { /* unknown chunk: skip */ }
            }
            if (nextPosition <= chunkStart) break
            buffer.position(nextPosition)
        }
    }

    private fun parseStringPool(chunkStart: Int, headerSize: Int, chunkSize: Int) {
        // Header layout (after the shared chunk header at chunkStart..+8):
        //   u32 stringCount, u32 styleCount, u32 flags, u32 stringsStart, u32 stylesStart
        require(headerSize >= 28) { "String pool header too small: $headerSize" }
        val stringCount = buffer.getInt()
        @Suppress("UNUSED_VARIABLE") val styleCount = buffer.getInt()
        val flags = buffer.getInt()
        val stringsStart = buffer.getInt()
        @Suppress("UNUSED_VARIABLE") val stylesStart = buffer.getInt()
        // Skip any extra bytes in the header beyond the 28 we just read.
        if (headerSize > 28) {
            buffer.position(chunkStart + headerSize)
        }
        val isUtf8 = (flags and STRING_POOL_FLAG_UTF8) != 0
        val offsets = IntArray(stringCount) { buffer.getInt() }

        val poolBase = chunkStart + stringsStart
        for (offset in offsets) {
            val absolute = poolBase + offset
            require(absolute >= 0 && absolute < chunkStart + chunkSize) {
                "String offset $offset out of range"
            }
            buffer.position(absolute)
            strings += if (isUtf8) decodeUtf8String() else decodeUtf16String()
        }
    }

    private fun decodeUtf8String(): String {
        // Two varint-style lengths: utf-16 char count, then utf-8 byte count.
        decodeUtf8Length()
        val byteLength = decodeUtf8Length()
        val data = ByteArray(byteLength)
        buffer.get(data)
        // The terminating NUL is not part of the byte length and is left in place.
        return data.toString(Charsets.UTF_8)
    }

    private fun decodeUtf8Length(): Int {
        val first = buffer.get().toInt() and 0xFF
        return if ((first and 0x80) != 0) {
            val second = buffer.get().toInt() and 0xFF
            ((first and 0x7F) shl 8) or second
        } else {
            first
        }
    }

    private fun decodeUtf16String(): String {
        val charCount = decodeUtf16Length()
        val chars = CharArray(charCount)
        for (i in 0 until charCount) {
            chars[i] = buffer.getShort().toInt().toChar()
        }
        // Skip the trailing NUL (2 bytes).
        if (buffer.remaining() >= 2) buffer.getShort()
        return String(chars)
    }

    private fun decodeUtf16Length(): Int {
        val first = buffer.getShort().toInt() and 0xFFFF
        return if ((first and 0x8000) != 0) {
            val second = buffer.getShort().toInt() and 0xFFFF
            ((first and 0x7FFF) shl 16) or second
        } else {
            first
        }
    }

    private fun parseStartElement(visitor: Visitor) {
        // Already past the chunk header (8 bytes type/headerSize/size).
        // Element header continues with:
        //   u32 lineNumber, u32 commentRef, u32 ns, u32 name,
        //   u16 attributeStart, u16 attributeSize, u16 attributeCount,
        //   u16 idIndex, u16 classIndex, u16 styleIndex
        @Suppress("UNUSED_VARIABLE") val lineNumber = buffer.getInt()
        @Suppress("UNUSED_VARIABLE") val commentRef = buffer.getInt()
        @Suppress("UNUSED_VARIABLE") val nsRef = buffer.getInt()
        val nameRef = buffer.getInt()
        val attrStart = buffer.getShort().toInt() and 0xFFFF
        val attrSize = buffer.getShort().toInt() and 0xFFFF
        val attrCount = buffer.getShort().toInt() and 0xFFFF
        // idIndex / classIndex / styleIndex are unused for our purposes.
        buffer.getShort(); buffer.getShort(); buffer.getShort()
        // Skip any padding between the fixed header and the attribute records.
        val attrBaseField = buffer.position() // position after attrStart was read
        // attrStart is measured from the field that holds it (the ResXMLTree_attrExt body),
        // i.e. starting at "ns" four ints before this point.
        val bodyStart = attrBaseField - 20
        val attrsAt = bodyStart + attrStart
        if (buffer.position() != attrsAt) buffer.position(attrsAt)

        val attrs = ArrayList<Attribute>(attrCount)
        for (i in 0 until attrCount) {
            val attrFieldStart = buffer.position()
            @Suppress("UNUSED_VARIABLE") val attrNs = buffer.getInt()
            val attrName = buffer.getInt()
            val rawValue = buffer.getInt()
            // Res_value: u16 valueSize, u8 res0, u8 dataType, u32 data
            @Suppress("UNUSED_VARIABLE") val valueSize = buffer.getShort().toInt() and 0xFFFF
            @Suppress("UNUSED_VARIABLE") val res0 = buffer.get().toInt() and 0xFF
            val dataType = buffer.get().toInt() and 0xFF
            val data = buffer.getInt()
            attrs += Attribute(
                name = stringAt(attrName),
                rawValue = if (rawValue == NO_STRING) null else stringAt(rawValue),
                dataType = dataType,
                data = data,
            )
            if (attrSize > 20) buffer.position(attrFieldStart + attrSize)
        }

        visitor.onStartElement(stringAt(nameRef), attrs)
    }

    private fun parseEndElement(visitor: Visitor) {
        @Suppress("UNUSED_VARIABLE") val lineNumber = buffer.getInt()
        @Suppress("UNUSED_VARIABLE") val commentRef = buffer.getInt()
        @Suppress("UNUSED_VARIABLE") val nsRef = buffer.getInt()
        val nameRef = buffer.getInt()
        visitor.onEndElement(stringAt(nameRef))
    }

    private fun stringAt(index: Int): String {
        if (index == NO_STRING || index < 0 || index >= strings.size) return ""
        return strings[index]
    }

    interface Visitor {
        fun onStartElement(name: String, attributes: List<Attribute>)
        fun onEndElement(name: String)
    }

    data class Attribute(
        val name: String,
        val rawValue: String?,
        val dataType: Int,
        val data: Int,
    ) {
        val stringValue: String?
            get() = rawValue
        val isString: Boolean get() = dataType == TYPE_STRING
        val isInt: Boolean get() = dataType == TYPE_INT_DEC || dataType == TYPE_INT_HEX
    }

    companion object {
        const val CHUNK_XML = 0x0003
        const val CHUNK_STRING_POOL = 0x0001
        const val CHUNK_RESOURCE_MAP = 0x0180
        const val CHUNK_XML_START_NS = 0x0100
        const val CHUNK_XML_END_NS = 0x0101
        const val CHUNK_XML_START_ELEMENT = 0x0102
        const val CHUNK_XML_END_ELEMENT = 0x0103
        const val CHUNK_XML_CDATA = 0x0104
        const val STRING_POOL_FLAG_UTF8 = 0x100
        const val NO_STRING = -1
        const val TYPE_REFERENCE = 1
        const val TYPE_STRING = 3
        const val TYPE_INT_DEC = 0x10
        const val TYPE_INT_HEX = 0x11
        const val TYPE_INT_BOOLEAN = 0x12
    }
}

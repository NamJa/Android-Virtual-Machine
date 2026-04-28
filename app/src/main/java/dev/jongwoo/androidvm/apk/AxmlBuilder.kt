package dev.jongwoo.androidvm.apk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds minimal Android binary XML payloads. Output mirrors the subset of the
 * format that [BinaryAxmlParser] understands: a single XML wrapper chunk, a
 * UTF-8 string pool, and a sequence of start/end element chunks with typed
 * attribute values (string and int_dec). Used by unit tests and by debug-only
 * diagnostics that need to synthesise APK manifests on the fly.
 */
class AxmlBuilder {

    sealed interface Value
    data class StringValue(val v: String) : Value
    data class IntValue(val v: Int) : Value

    data class Attr(val name: String, val value: Value) {
        companion object {
            fun string(name: String, v: String): Attr = Attr(name, StringValue(v))
            fun int(name: String, v: Int): Attr = Attr(name, IntValue(v))
        }
    }

    private sealed interface Event
    private data class StartEvent(val name: String, val attrs: List<Attr>) : Event
    private data class EndEvent(val name: String) : Event

    private val events = mutableListOf<Event>()

    fun start(name: String, vararg attrs: Attr): AxmlBuilder {
        events += StartEvent(name, attrs.toList())
        return this
    }

    fun end(name: String): AxmlBuilder {
        events += EndEvent(name)
        return this
    }

    fun element(name: String, vararg attrs: Attr): AxmlBuilder = start(name, *attrs).end(name)

    fun build(): ByteArray {
        val pool = LinkedHashMap<String, Int>()
        fun intern(s: String): Int = pool.getOrPut(s) { pool.size }

        for (event in events) {
            when (event) {
                is StartEvent -> {
                    intern(event.name)
                    for (attr in event.attrs) {
                        intern(attr.name)
                        if (attr.value is StringValue) intern(attr.value.v)
                    }
                }
                is EndEvent -> intern(event.name)
            }
        }

        val body = ByteArrayOutputStream()
        body.write(stringPoolChunkUtf8(pool.keys.toList()))
        for (event in events) {
            when (event) {
                is StartEvent -> body.write(startElementChunk(event.name, event.attrs, ::intern))
                is EndEvent -> body.write(endElementChunk(intern(event.name)))
            }
        }

        val totalSize = 8 + body.size()
        val output = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        output.putShort(0x0003.toShort())
        output.putShort(0x0008.toShort())
        output.putInt(totalSize)
        output.put(body.toByteArray())
        return output.array()
    }

    private fun stringPoolChunkUtf8(strings: List<String>): ByteArray {
        val stringDataBuffer = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        for ((i, s) in strings.withIndex()) {
            offsets[i] = stringDataBuffer.size()
            val bytes = s.toByteArray(Charsets.UTF_8)
            require(s.length < 0x80) { "test fixture string '$s' too long for single-byte utf16len" }
            require(bytes.size < 0x80) { "test fixture string '$s' too long for single-byte utf8len" }
            stringDataBuffer.write(s.length)
            stringDataBuffer.write(bytes.size)
            stringDataBuffer.write(bytes)
            stringDataBuffer.write(0)
        }
        while (stringDataBuffer.size() % 4 != 0) stringDataBuffer.write(0)

        val headerSize = 28
        val offsetsSize = strings.size * 4
        val stringsStart = headerSize + offsetsSize
        val totalChunkSize = stringsStart + stringDataBuffer.size()

        val buf = ByteBuffer.allocate(totalChunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0001.toShort())
        buf.putShort(headerSize.toShort())
        buf.putInt(totalChunkSize)
        buf.putInt(strings.size)
        buf.putInt(0) // styleCount
        buf.putInt(0x100) // flags = UTF-8
        buf.putInt(stringsStart)
        buf.putInt(0) // stylesStart
        for (off in offsets) buf.putInt(off)
        buf.put(stringDataBuffer.toByteArray())
        return buf.array()
    }

    private fun startElementChunk(name: String, attrs: List<Attr>, intern: (String) -> Int): ByteArray {
        val bodySize = 20 + attrs.size * 20
        val chunkSize = 8 + 8 + bodySize
        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0102.toShort())
        buf.putShort(16.toShort())
        buf.putInt(chunkSize)
        buf.putInt(0) // line
        buf.putInt(-1) // commentRef
        buf.putInt(-1) // ns
        buf.putInt(intern(name))
        buf.putShort(20.toShort()) // attrStart
        buf.putShort(20.toShort()) // attrSize
        buf.putShort(attrs.size.toShort())
        buf.putShort(0.toShort()) // idIndex
        buf.putShort(0.toShort()) // classIndex
        buf.putShort(0.toShort()) // styleIndex

        for (attr in attrs) {
            buf.putInt(-1) // ns
            buf.putInt(intern(attr.name))
            when (val v = attr.value) {
                is StringValue -> {
                    val ref = intern(v.v)
                    buf.putInt(ref) // rawValue
                    buf.putShort(8.toShort()) // res value size
                    buf.put(0) // res0
                    buf.put(0x03) // dataType STRING
                    buf.putInt(ref)
                }
                is IntValue -> {
                    buf.putInt(-1)
                    buf.putShort(8.toShort())
                    buf.put(0)
                    buf.put(0x10) // dataType INT_DEC
                    buf.putInt(v.v)
                }
            }
        }
        return buf.array()
    }

    private fun endElementChunk(nameRef: Int): ByteArray {
        val chunkSize = 8 + 8 + 8
        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0103.toShort())
        buf.putShort(16.toShort())
        buf.putInt(chunkSize)
        buf.putInt(0) // line
        buf.putInt(-1) // comment
        buf.putInt(-1) // ns
        buf.putInt(nameRef)
        return buf.array()
    }
}

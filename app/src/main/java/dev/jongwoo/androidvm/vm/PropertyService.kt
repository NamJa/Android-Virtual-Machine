package dev.jongwoo.androidvm.vm

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin twin of `app/src/main/cpp/property/property_service.{h,cpp}`. Mirrors the
 * boot-time seed exactly so JVM tests pin the property values that on-device bionic reads
 * during boot.
 */
class PropertyService {
    private val lock = Any()
    private val values = sortedMapOf<String, String>()
    private var bootCompletedFlag: Boolean = false

    init {
        seedDefaultBootProperties()
    }

    fun set(key: String, value: String) {
        synchronized(lock) { values[key] = value }
    }

    fun get(key: String, fallback: String = ""): String =
        synchronized(lock) { values[key] ?: fallback }

    fun has(key: String): Boolean = synchronized(lock) { values.containsKey(key) }

    fun size(): Int = synchronized(lock) { values.size }

    fun snapshot(): List<Pair<String, String>> = synchronized(lock) {
        values.map { it.key to it.value }
    }

    fun markBootCompleted(timestampMillis: Long) {
        synchronized(lock) {
            bootCompletedFlag = true
            values["sys.boot_completed"] = "1"
            values["dev.bootcomplete"]   = "1"
            values["ro.runtime.firstboot"] = timestampMillis.toString()
        }
    }

    fun bootCompleted(): Boolean = synchronized(lock) { bootCompletedFlag }

    private fun seedDefaultBootProperties() {
        synchronized(lock) {
            values["ro.build.version.release"]  = "7.1.2"
            values["ro.build.version.sdk"]      = "25"
            values["ro.product.cpu.abi"]        = "arm64-v8a"
            values["ro.product.cpu.abilist"]    = "arm64-v8a"
            values["ro.product.cpu.abilist64"]  = "arm64-v8a"
            values["ro.zygote"]                 = "zygote64"
            values["ro.kernel.qemu"]            = "0"
            values["init.svc.zygote"]           = "running"
            values["init.svc.servicemanager"]   = "running"
            values["init.svc.surfaceflinger"]   = "running"
            values["dalvik.vm.heapsize"]        = "256m"
            values["dalvik.vm.dex2oat-flags"]   = "--compiler-filter=quicken"
            values["persist.sys.locale"]        = "en-US"
            values["sys.boot_completed"]        = "0"
        }
    }
}

/**
 * Pure-Kotlin twin of `property/property_area.cpp`. The byte layout is the
 * `/dev/__properties__` mmap surface that the guest libc reads.
 */
object PropertyArea {
    const val MAGIC: Int = 0x504D5641
    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 16

    fun build(entries: List<Pair<String, String>>): ByteArray {
        val sorted = entries.sortedBy { it.first }
        val body = mutableListOf<ByteArray>()
        var bodyLen = 0
        for ((k, v) in sorted) {
            if (k.length > 0xFFFF || v.length > 0xFFFF) continue
            val kb = k.toByteArray(Charsets.UTF_8)
            val vb = v.toByteArray(Charsets.UTF_8)
            val entry = ByteBuffer.allocate(4 + kb.size + vb.size).order(ByteOrder.LITTLE_ENDIAN)
            entry.putShort(kb.size.toShort())
            entry.putShort(vb.size.toShort())
            entry.put(kb)
            entry.put(vb)
            body += entry.array()
            bodyLen += entry.array().size
            // 4-byte alignment padding
            val pad = (4 - bodyLen % 4) % 4
            if (pad > 0) {
                body += ByteArray(pad)
                bodyLen += pad
            }
        }
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(MAGIC)
        header.putInt(VERSION)
        header.putInt(sorted.size)
        header.putInt(bodyLen)
        val out = ByteArray(HEADER_SIZE + bodyLen)
        System.arraycopy(header.array(), 0, out, 0, HEADER_SIZE)
        var offset = HEADER_SIZE
        for (chunk in body) {
            System.arraycopy(chunk, 0, out, offset, chunk.size)
            offset += chunk.size
        }
        return out
    }

    fun decode(bytes: ByteArray): List<Pair<String, String>> {
        if (bytes.size < HEADER_SIZE) return emptyList()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.getInt(0)
        val version = buf.getInt(4)
        val count = buf.getInt(8)
        val used = buf.getInt(12)
        if (magic != MAGIC || version != VERSION) return emptyList()
        if (HEADER_SIZE + used > bytes.size) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        var cursor = HEADER_SIZE
        repeat(count) {
            if (cursor + 4 > bytes.size) return out
            val kl = buf.getShort(cursor + 0).toInt() and 0xFFFF
            val vl = buf.getShort(cursor + 2).toInt() and 0xFFFF
            cursor += 4
            if (cursor + kl + vl > bytes.size) return out
            val key = String(bytes, cursor, kl, Charsets.UTF_8); cursor += kl
            val value = String(bytes, cursor, vl, Charsets.UTF_8); cursor += vl
            out += key to value
            while (cursor % 4 != 0 && cursor < bytes.size) cursor++
        }
        return out
    }
}

/** Pure-Kotlin twin of `property/build_props.cpp`. */
object BuildPropsParser {
    fun parse(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val eq = line.indexOf('=')
            if (eq < 0) return@forEach
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            if (key.isEmpty()) return@forEach
            out += key to value
        }
        return out
    }
}

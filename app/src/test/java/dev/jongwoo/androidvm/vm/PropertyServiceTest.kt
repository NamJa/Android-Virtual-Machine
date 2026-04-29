package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertyServiceTest {
    @Test
    fun seededBootPropertiesContainTheDocumentedRequiredSet() {
        val s = PropertyService()
        // The doc § C.3.c lists these as boot-time required.
        listOf(
            "ro.zygote", "ro.kernel.qemu", "init.svc.zygote", "init.svc.servicemanager",
            "init.svc.surfaceflinger", "dalvik.vm.heapsize", "dalvik.vm.dex2oat-flags",
            "persist.sys.locale", "sys.boot_completed", "ro.build.version.release",
        ).forEach { key ->
            assertTrue("seed should include $key", s.has(key))
        }
        assertEquals("zygote64", s.get("ro.zygote"))
        assertEquals("running", s.get("init.svc.zygote"))
        assertEquals("0", s.get("sys.boot_completed"))
        assertEquals("7.1.2", s.get("ro.build.version.release"))
    }

    @Test
    fun setGetRoundTrips() {
        val s = PropertyService()
        s.set("debug.test.key", "value-1")
        assertEquals("value-1", s.get("debug.test.key"))
        s.set("debug.test.key", "value-2")
        assertEquals("value-2", s.get("debug.test.key"))
    }

    @Test
    fun unknownKeyReturnsFallback() {
        val s = PropertyService()
        assertEquals("default", s.get("not-a-key", "default"))
        assertEquals("", s.get("not-a-key"))
    }

    @Test
    fun markBootCompletedTogglesFlagAndProperties() {
        val s = PropertyService()
        assertFalse(s.bootCompleted())
        assertEquals("0", s.get("sys.boot_completed"))
        s.markBootCompleted(1_700_000_000_000)
        assertTrue(s.bootCompleted())
        assertEquals("1", s.get("sys.boot_completed"))
        assertEquals("1", s.get("dev.bootcomplete"))
        assertEquals("1700000000000", s.get("ro.runtime.firstboot"))
    }
}

class PropertyAreaTest {
    @Test
    fun buildEmptyAreaProducesHeaderOnly() {
        val bytes = PropertyArea.build(emptyList())
        assertEquals(PropertyArea.HEADER_SIZE, bytes.size)
        // Magic at [0..3].
        assertEquals(0x41, bytes[0].toInt() and 0xFF)
        assertEquals(0x56, bytes[1].toInt() and 0xFF)
        assertEquals(0x4D, bytes[2].toInt() and 0xFF)
        assertEquals(0x50, bytes[3].toInt() and 0xFF)
    }

    @Test
    fun buildAndDecodeRoundTripsAllEntries() {
        val entries = listOf(
            "ro.zygote" to "zygote64",
            "init.svc.zygote" to "running",
            "sys.boot_completed" to "1",
        )
        val bytes = PropertyArea.build(entries)
        val decoded = PropertyArea.decode(bytes)
        // The area is sorted by key.
        assertEquals(entries.sortedBy { it.first }, decoded)
    }

    @Test
    fun decodeRejectsCorruptMagic() {
        val good = PropertyArea.build(listOf("k" to "v"))
        val bad = good.copyOf()
        bad[0] = 0
        assertEquals(emptyList<Pair<String, String>>(), PropertyArea.decode(bad))
    }

    @Test
    fun decodeRejectsTruncated() {
        assertEquals(emptyList<Pair<String, String>>(), PropertyArea.decode(ByteArray(8)))
    }

    @Test
    fun longEntriesAreEncodedFaithfully() {
        val k = "k".repeat(200)
        val v = "v".repeat(500)
        val bytes = PropertyArea.build(listOf(k to v))
        val decoded = PropertyArea.decode(bytes)
        assertEquals(1, decoded.size)
        assertEquals(k, decoded[0].first)
        assertEquals(v, decoded[0].second)
    }

    @Test
    fun areaLayoutMatchesNativeContractMagicBytes() {
        // 'AVMP' little-endian.
        assertEquals(0x504D5641, PropertyArea.MAGIC)
        assertEquals(1, PropertyArea.VERSION)
        assertNotEquals(0, PropertyArea.HEADER_SIZE)
    }
}

class BuildPropsParserTest {
    @Test
    fun parsesKeyEqValueLines() {
        val text = """
            ro.zygote=zygote64
            ro.kernel.qemu=0
        """.trimIndent()
        val out = BuildPropsParser.parse(text)
        assertEquals(2, out.size)
        assertEquals("ro.zygote" to "zygote64", out[0])
        assertEquals("ro.kernel.qemu" to "0", out[1])
    }

    @Test
    fun ignoresCommentsAndBlankLines() {
        val text = """

            # comment
              # indented comment
            ro.zygote=zygote64

        """.trimIndent()
        val out = BuildPropsParser.parse(text)
        assertEquals(1, out.size)
        assertEquals("ro.zygote", out[0].first)
    }

    @Test
    fun trimsWhitespaceAroundEquals() {
        val out = BuildPropsParser.parse("  ro.foo  =   bar baz   ")
        assertEquals(1, out.size)
        assertEquals("ro.foo" to "bar baz", out[0])
    }

    @Test
    fun valuesContainingEqualsKeepTheTail() {
        val out = BuildPropsParser.parse("dalvik.vm.dex2oat-flags=--compiler-filter=quicken")
        assertEquals(1, out.size)
        assertEquals("--compiler-filter=quicken", out[0].second)
    }

    @Test
    fun keyWithoutEqualsIsSkipped() {
        val out = BuildPropsParser.parse("rogue_line\nfoo=bar")
        assertEquals(1, out.size)
        assertEquals("foo" to "bar", out[0])
    }
}

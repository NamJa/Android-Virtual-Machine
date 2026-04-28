package dev.jongwoo.androidvm.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryAxmlParserTest {

    @Test
    fun parses_singleElementWithStringAndIntAttributes() {
        val bytes = AxmlBuilder()
            .element(
                "manifest",
                AxmlBuilder.Attr.string("package", "com.example.demo"),
                AxmlBuilder.Attr.int("versionCode", 17),
            )
            .build()

        val events = collect(bytes)

        assertEquals(2, events.size)
        val start = events[0] as Event.Start
        val end = events[1] as Event.End
        assertEquals("manifest", start.name)
        assertEquals("manifest", end.name)
        assertEquals(2, start.attrs.size)
        val pkg = start.attrs[0]
        assertEquals("package", pkg.name)
        assertEquals("com.example.demo", pkg.stringValue)
        assertTrue(pkg.isString)
        val version = start.attrs[1]
        assertEquals("versionCode", version.name)
        assertNull(version.stringValue)
        assertTrue(version.isInt)
        assertEquals(17, version.data)
    }

    @Test
    fun parses_nestedElements() {
        val bytes = AxmlBuilder()
            .start("manifest", AxmlBuilder.Attr.string("package", "com.example"))
            .start("application")
            .start("activity", AxmlBuilder.Attr.string("name", ".Main"))
            .start("intent-filter")
            .element("action", AxmlBuilder.Attr.string("name", "android.intent.action.MAIN"))
            .element("category", AxmlBuilder.Attr.string("name", "android.intent.category.LAUNCHER"))
            .end("intent-filter")
            .end("activity")
            .end("application")
            .end("manifest")
            .build()

        val events = collect(bytes)
        val starts = events.filterIsInstance<Event.Start>().map { it.name }
        val ends = events.filterIsInstance<Event.End>().map { it.name }
        assertEquals(
            listOf("manifest", "application", "activity", "intent-filter", "action", "category"),
            starts,
        )
        assertEquals(
            listOf("action", "category", "intent-filter", "activity", "application", "manifest"),
            ends,
        )
    }

    private sealed interface Event {
        data class Start(val name: String, val attrs: List<BinaryAxmlParser.Attribute>) : Event
        data class End(val name: String) : Event
    }

    private fun collect(bytes: ByteArray): List<Event> {
        val collected = mutableListOf<Event>()
        BinaryAxmlParser(bytes).accept(object : BinaryAxmlParser.Visitor {
            override fun onStartElement(name: String, attributes: List<BinaryAxmlParser.Attribute>) {
                collected += Event.Start(name, attributes)
            }

            override fun onEndElement(name: String) {
                collected += Event.End(name)
            }
        })
        return collected
    }
}

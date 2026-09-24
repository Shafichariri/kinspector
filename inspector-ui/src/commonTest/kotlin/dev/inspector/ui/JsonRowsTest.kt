package dev.inspector.ui

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tree as lines: what folds, what a folded line says, and that a number keeps its digits. */
class JsonRowsTest {

    private val body = """{"id":1234567890123456789,"user":{"name":"Ada","roles":["a","b"]},"empty":{},"n":null}"""
    private val root = parseJsonTree(body)!!

    @Test
    fun `fully open it draws a line per scalar and two per container`() {
        val rows = jsonRows(root, emptySet())
        // {  id  user{  name  roles[  a  b  ]  }  empty{}  n  }
        assertEquals(12, rows.size)
        assertTrue(rows.first() is JsonRow.Open && rows.last() is JsonRow.Close)
    }

    @Test
    fun `a folded container is one line that counts what it holds`() {
        val rows = jsonRows(root, setOf("/user"))
        val folded = rows.single { it.path == "/user" } as JsonRow.Summary
        assertEquals(2, folded.size)
        assertEquals("user", folded.key)
        assertTrue(rows.none { it.path.startsWith("/user/") })
    }

    @Test
    fun `an empty container is a leaf and cannot fold`() {
        assertTrue(jsonRows(root, emptySet()).single { it.path == "/empty" } is JsonRow.Leaf)
        assertTrue(jsonContainers(root).none { it.path == "/empty" })
    }

    @Test
    fun `a 64-bit id keeps every digit`() {
        // The web lost this to JSON.parse until it stopped using it. kotlinx keeps the literal.
        val id = jsonRows(root, emptySet()).single { it.path == "/id" } as JsonRow.Leaf
        assertEquals("1234567890123456789", (id.value as JsonPrimitive).content)
    }

    @Test
    fun `a key with a slash cannot pass for a nested path`() {
        val tricky = parseJsonTree("""{"a/b":{"c":1},"a":{"b":{"c":2}}}""")!!
        val paths = jsonContainers(tricky).map { it.path }
        assertEquals(listOf("", "/a~1b", "/a", "/a/b"), paths)
    }

    @Test
    fun `only objects and arrays make a tree`() {
        assertNull(parseJsonTree("42"))
        assertNull(parseJsonTree("\"text\""))
        assertNull(parseJsonTree("{\"cut\":"))
    }

    @Test
    fun `a big body opens folded below depth two and a small one opens whole`() {
        assertEquals(emptySet(), initialTreeState(root).collapsed)
        val big = parseJsonTree("[" + (1..300).joinToString(",") { """{"i":$it,"x":{"y":1}}""" } + "]")!!
        val state = initialTreeState(big)
        assertTrue(state.collapsed.isNotEmpty())
        assertTrue(jsonContainers(big).filter { it.path in state.collapsed }.all { it.depth >= 2 })
    }
}

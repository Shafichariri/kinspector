package dev.inspector.daemon

import dev.inspector.daemon.service.PeerArgv
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The peer argv token is the main class, spelled out, and it must stay both.
 *
 * [PeerArgv.MAIN_CLASS] is matched by exact equality against other processes' arguments, so it is
 * also how this daemon recognises peers launched by *older* builds. Moving `Main.kt`, or
 * "correcting" the constant after a package move, would leave a daemon that still starts and no
 * longer sees any sibling — nothing else fails when that happens, which is why the literal is
 * asserted here rather than read back from wherever the class happens to live.
 *
 * The second half asks the class loader, so a constant that names a class with no entry point is
 * caught too: the token would then match nothing a launcher ever produced.
 */
class MainClassTest {

    @Test
    fun `the peer argv token is the main class name older daemons were launched with`() {
        assertEquals("dev.inspector.daemon.MainKt", PeerArgv.MAIN_CLASS)
    }

    @Test
    fun `the peer argv token resolves to a public static void main of String array`() {
        val loader = MainClassTest::class.java.classLoader
        val main = Class.forName(PeerArgv.MAIN_CLASS, false, loader)
            .getMethod("main", Array<String>::class.java)
        assertTrue(Modifier.isPublic(main.modifiers), "main is not public")
        assertTrue(Modifier.isStatic(main.modifiers), "main is not static")
        assertEquals(Void.TYPE, main.returnType, "main does not return void")
    }
}

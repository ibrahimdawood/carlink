package dev.caraalink.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellDisplayFlagsTest {

    private fun has(flags: Int, bit: Int) = flags and (1 shl bit) != 0

    @Test
    fun `android 10 - public, own content, touch, closes apps when removed`() {
        val f = ShellDisplayService.displayFlags(29)
        assertTrue(has(f, 0) && has(f, 3) && has(f, 6) && has(f, 8))
        assertEquals(0, f and (1 shl 10)) // TRUSTED needs Android 13
    }

    @Test
    fun `android 13 and 14 add trusted and own focus`() {
        assertTrue(has(ShellDisplayService.displayFlags(33), 10))
        assertTrue(has(ShellDisplayService.displayFlags(34), 14))
    }

    @Test
    fun `never rotates with content or mirrors the phone`() {
        for (sdk in 29..35) {
            val f = ShellDisplayService.displayFlags(sdk)
            assertEquals(0, f and (1 shl 7)) // ROTATES_WITH_CONTENT
            assertEquals(0, f and (1 shl 4)) // AUTO_MIRROR
        }
    }
}

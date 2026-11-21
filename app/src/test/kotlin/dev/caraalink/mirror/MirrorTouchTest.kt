package dev.caraalink.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MirrorTouchTest {

    private fun assertPoint(expectedX: Float, expectedY: Float, p: MirrorTouch.Point?) {
        requireNotNull(p) { "expected a point, got a bar" }
        assertEquals(expectedX, p.x, 0.5f)
        assertEquals(expectedY, p.y, 0.5f)
    }

    // Portrait phone 1080x2400 on a 1920x720 car frame: scale 0.3, picture 324 wide, bars of 798 each side.

    @Test
    fun `portrait phone - centre of the frame is the centre of the phone`() {
        assertPoint(540f, 1200f, MirrorTouch.map(1920, 720, 1080, 2400, 960f, 360f))
    }

    @Test
    fun `portrait phone - touches on the side bars hit nothing`() {
        assertNull(MirrorTouch.map(1920, 720, 1080, 2400, 100f, 360f))
        assertNull(MirrorTouch.map(1920, 720, 1080, 2400, 1800f, 360f))
        assertNull(MirrorTouch.map(1920, 720, 1080, 2400, 797f, 360f))
    }

    @Test
    fun `portrait phone - picture corners map to phone corners`() {
        assertPoint(0f, 0f, MirrorTouch.map(1920, 720, 1080, 2400, 798f, 0f))
        assertPoint(1079f, 2399f, MirrorTouch.map(1920, 720, 1080, 2400, 1122f, 720f))
    }

    // Landscape phone 2400x1080 on 1920x720: scale 0.6667 by height... 1920/2400=0.8, 720/1080=0.667 -> 0.667,
    // picture 1600 wide, bars of 160 each side.

    @Test
    fun `landscape phone - maps through the narrower bars`() {
        assertPoint(1200f, 540f, MirrorTouch.map(1920, 720, 2400, 1080, 960f, 360f))
        assertPoint(0f, 0f, MirrorTouch.map(1920, 720, 2400, 1080, 160f, 0f))
        assertNull(MirrorTouch.map(1920, 720, 2400, 1080, 150f, 360f))
    }

    @Test
    fun `same aspect - no bars, straight scale`() {
        assertPoint(1200f, 540f, MirrorTouch.map(800, 360, 2400, 1080, 400f, 180f))
        assertPoint(0f, 0f, MirrorTouch.map(800, 360, 2400, 1080, 0f, 0f))
    }

    @Test
    fun `bars above and below when the frame is taller than the phone`() {
        // 1000x1000 frame, 2000x1000 phone: scale 0.5, picture 1000x500, bars of 250 top and bottom.
        assertNull(MirrorTouch.map(1000, 1000, 2000, 1000, 500f, 100f))
        assertPoint(1000f, 500f, MirrorTouch.map(1000, 1000, 2000, 1000, 500f, 500f))
    }

    @Test
    fun `unknown sizes hit nothing`() {
        assertNull(MirrorTouch.map(0, 720, 1080, 2400, 10f, 10f))
        assertNull(MirrorTouch.map(1920, 720, 0, 0, 10f, 10f))
    }
}

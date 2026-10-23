package wtf.mahdi.glassesconnect

import org.junit.Assert.assertEquals
import org.junit.Test

class GallerySaverTest {
    @Test
    fun fileNameMatchesMediaStore() {
        // Name from /v1/filelists; MediaStore stored it as "2026-10-16 23_37.ogg" (device, 2026-10-23).
        assertEquals("2026-10-16 23_37.ogg", GallerySaver.fileName("2026-10-16 23:37.ogg"))
        assertEquals("IMG_20261012212354653.heic", GallerySaver.fileName("IMG_20261012212354653.heic"))
        assertEquals("a_b_c_d.jpg", GallerySaver.fileName("a?b|c\"d.jpg"))
    }
}

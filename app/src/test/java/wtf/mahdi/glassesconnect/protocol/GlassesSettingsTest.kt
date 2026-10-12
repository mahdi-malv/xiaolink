package wtf.mahdi.glassesconnect.protocol

import org.junit.Assert.*
import org.junit.Test

class GlassesSettingsTest {
    private fun group(title: String) = GlassesSettings.groups.first { it.title == title }
    private fun item(g: GlassesSettings.Group, label: String) = g.items.first { it.label.startsWith(label) }

    @Test fun parseEditAndWritePreserveUnknownFields() {
        val media = group("Media"); val ota = group("OTA")
        // mediaSetting {shootingMode 2, unknown f99 7}, otaSetting {extraOption {autoUpgrade 1}}
        val setting = Proto.encode {
            message(7) { uint(1, 2); uint(99, 7) }
            message(9) { message(2) { uint(2, 1) } }
        }
        val reply = GlassesCommands.system(14, Proto.encode { bytes(35, setting) })
        val parsed = GlassesSettings.parse(reply)!!
        assertEquals(2L, GlassesSettings.get(parsed, media, item(media, "Shooting mode")))
        assertEquals(1L, GlassesSettings.get(parsed, ota, item(ota, "Auto upgrade")))
        assertNull(GlassesSettings.get(parsed, ota, item(ota, "Auto download")))

        var edited = GlassesSettings.set(parsed, media, item(media, "Shooting mode"), 5)
        edited = GlassesSettings.set(edited, ota, item(ota, "Auto download"), 1)
        assertEquals(5L, GlassesSettings.get(edited, media, item(media, "Shooting mode")))
        assertEquals(1L, GlassesSettings.get(edited, ota, item(ota, "Auto download")))
        assertEquals(1L, GlassesSettings.get(edited, ota, item(ota, "Auto upgrade")))

        // Write = System id 15, f35 {f7 media only}; unknown field kept.
        val write = Proto.decode(GlassesSettings.write(media, edited))
        assertEquals(15L, Proto.scalar(write, 2))
        val sent = Proto.decode(Proto.bytes(Proto.decode(Proto.bytes(write, 4)!!), 35)!!)
        assertEquals(listOf(7), sent.map { it.number })
        val sentMedia = Proto.decode(sent[0].data)
        assertEquals(5L, Proto.scalar(sentMedia, 1)); assertEquals(7L, Proto.scalar(sentMedia, 99))
    }

    @Test fun signedFieldsRoundTripNegative() {
        val light = group("Indicator light"); val charging = item(light, "Charging light")
        val edited = GlassesSettings.set(byteArrayOf(), light, charging, -1)
        assertEquals(-1L, GlassesSettings.get(edited, light, charging))
    }

    @Test fun requestUsesAllGroupBits() {
        assertEquals(1023, GlassesSettings.allBits)
        assertNull(GlassesSettings.parse(GlassesCommands.system(15)))
    }
}

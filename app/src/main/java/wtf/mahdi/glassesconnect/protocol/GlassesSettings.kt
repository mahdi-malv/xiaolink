package wtf.mahdi.glassesconnect.protocol

/**
 * SystemProtos.SystemSetting: read with System id 14 (f34 Request {items bitmask}), reply and write (id 15) carry System f35.
 * Value meanings from the official settings UI (MiWearSettingFragment, GestureSettingFragment, *DialogKt).
 * Numbers without labels (gestures, wear, eye tracking, ...) are never set by the official app; meanings unknown.
 * Edits are applied to the raw bytes read from the glasses, so unknown fields survive a write.
 */
object GlassesSettings {
    sealed interface Kind
    data object Bool : Kind
    data class Number(val min: Long = 0, val max: Long = 255) : Kind
    data class Choice(val options: Map<Long, String>) : Kind

    /** [path] is relative to the group message; [signed] marks int32 fields. */
    data class Item(val path: List<Int>, val label: String, val kind: Kind, val signed: Boolean = false)
    data class Group(val field: Int, val bit: Int, val title: String, val items: List<Item>)

    const val SYSTEM_FIELD = 35
    /** 0 = unsupported (official app hides the item), 1 = on, 2 = off. */
    private val onOff = Choice(mapOf(1L to "On", 2L to "Off"))

    val groups: List<Group> = listOf(
        Group(5, 16, "Indicator light", listOf(
            Item(listOf(1), "Brightness", Choice(mapOf(4L to "Lower", 1L to "Low", 2L to "Middle", 3L to "High"))),
            Item(listOf(2), "Lower level supported (read-only)", Number()),
            Item(listOf(3), "Charging light", onOff, signed = true),
        )),
        Group(6, 32, "AI assistant", listOf(
            Item(listOf(1), "Voice wake-up", Bool),
            Item(listOf(2), "Camera access", Bool),
            Item(listOf(3), "Collaboration wake-up", Bool),
            Item(listOf(4), "Supported tones (read-only; 3 = both)", Number()),
            Item(listOf(5), "Voice tone", Choice(mapOf(1L to "Suger", 2L to "Pomelo"))),
            Item(listOf(6), "Eye tracking", Number(-1, 255), signed = true),
        )),
        Group(7, 64, "Media", listOf(
            Item(listOf(1), "Shooting mode", Choice(mapOf(1L to "Tap photo / hold video", 2L to "Tap video / hold photo"))),
            Item(listOf(2), "Video duration", Choice((listOf(15L, 60L, 120L, 180L, 300L, 600L).associateWith { "${it}s" } + listOf(1200L, 1800L, 2400L).associateWith { "${it}s (override)" }))),
            Item(listOf(3), "Auto sync (official app always sends off)", Bool),
            Item(listOf(4), "Mic pickup", Choice(mapOf(1L to "All directions", 2L to "Forward"))),
            Item(listOf(5), "Overheat protection", Choice(mapOf(1L to "On", 2L to "Off (risky)"))),
            Item(listOf(6), "Shield reminder", onOff),
            Item(listOf(7), "Hint volume", Choice(mapOf(1L to "Auto", 2L to "High", 3L to "Middle", 4L to "Low"))),
            Item(listOf(8), "Record gesture", Choice(mapOf(1L to "Two-finger tap", 2L to "Two-finger hold", 3L to "None"))),
            Item(listOf(9), "Preview on watch", Number()),
            Item(listOf(10), "Live photo", Number()),
            Item(listOf(11), "Ratio / quality", Number()),
            Item(listOf(12), "Slow-motion duration", Number(0, 3600)),
            Item(listOf(13), "Exercise delay duration", Number(0, 3600)),
        )),
        Group(8, 128, "Headset", listOf(
            Item(listOf(1), "Wear detection", Bool),
            Item(listOf(2), "Adaptive volume", Bool),
            Item(listOf(3), "Prevent sound leak", Bool),
            Item(listOf(4), "Music control", Choice(mapOf(1L to "Single tap", 2L to "Double tap"))),
            Item(listOf(5), "Sound effect", Number()),
        )),
        Group(9, 256, "OTA", listOf(
            Item(listOf(1), "Silent update", Bool),
            Item(listOf(2, 1), "Auto download", Bool),
            Item(listOf(2, 2), "Auto upgrade", Bool),
        )),
        Group(10, 512, "Gestures", listOf(
            Item(listOf(1, 1), "Touchpad: tap", Number()),
            Item(listOf(1, 2), "Touchpad: double tap", Number()),
            Item(listOf(1, 3), "Touchpad: triple tap", Number()),
            Item(listOf(1, 4), "Touchpad: long press", Number()),
            Item(listOf(1, 5), "Touchpad: swipe back→front", Number()),
            Item(listOf(1, 6), "Touchpad: swipe front→back", Number()),
            Item(listOf(1, 7), "Touchpad: two-finger tap", Number()),
            Item(listOf(1, 8), "Touchpad: two-finger long press", Number()),
            Item(listOf(2, 1), "Button: short press", Number()),
            Item(listOf(2, 2), "Button: long press", Number()),
        )),
        Group(1, 1, "Notifications", listOf(
            Item(listOf(1), "Auto screen", Number(-1, 255), signed = true),
            Item(listOf(2), "Only on lock screen", Number(-1, 255), signed = true),
            Item(listOf(3), "Only when worn", Number(-1, 255), signed = true),
            Item(listOf(4), "Wrist drop", Number(-1, 255), signed = true),
        )),
        Group(2, 2, "Do not disturb", listOf(Item(listOf(1), "Sync with phone", Bool))),
        Group(3, 4, "Wear", listOf(
            Item(listOf(1), "Wear mode", Number(-1, 255), signed = true),
            Item(listOf(2, 1), "Power mode", Number(-1, 255), signed = true),
        )),
        Group(4, 8, "Disconnect reminder", listOf(Item(listOf(1), "Alert", Bool))),
    )

    val allBits = groups.sumOf { it.bit }

    fun request(bits: Int = allBits): ByteArray = GlassesCommands.system(14, Proto.encode { message(34) { uint(1, bits) } })

    /** Write sends one group only, like the official SetMediaSetting (only mediaSetting is set). */
    fun write(group: Group, setting: ByteArray): ByteArray {
        val sub = Proto.bytes(Proto.decode(setting), group.field) ?: byteArrayOf()
        return GlassesCommands.system(15, Proto.encode { message(SYSTEM_FIELD) { bytes(group.field, sub) } })
    }

    /** SystemSetting bytes from a type 2 / id 14 reply, else null. */
    fun parse(packet: ByteArray): ByteArray? = runCatching {
        val envelope = Proto.decode(packet)
        if (Proto.scalar(envelope, 1) != 2L || Proto.scalar(envelope, 2) != 14L) return null
        Proto.bytes(Proto.decode(Proto.bytes(envelope, 4) ?: return null), SYSTEM_FIELD)
    }.getOrNull()

    fun get(setting: ByteArray, group: Group, item: Item): Long? {
        var fields = Proto.decode(setting)
        val path = listOf(group.field) + item.path
        for (n in path.dropLast(1)) fields = Proto.decode(Proto.bytes(fields, n) ?: return null)
        val raw = Proto.scalar(fields, path.last()) ?: return null
        return if (item.signed) raw.toInt().toLong() else raw
    }

    fun set(setting: ByteArray, group: Group, item: Item, value: Long): ByteArray =
        setPath(setting, listOf(group.field) + item.path, value)

    private fun setPath(message: ByteArray, path: List<Int>, value: Long): ByteArray {
        val fields = Proto.decode(message)
        val n = path.first()
        val replacement = if (path.size == 1) Proto.Field(n, 0, value)
            else Proto.Field(n, 2, data = setPath(Proto.bytes(fields, n) ?: byteArrayOf(), path.drop(1), value))
        val index = fields.indexOfLast { it.number == n }
        val updated = if (index < 0) fields + replacement
            else fields.filterIndexed { i, f -> f.number != n || i == index }.map { if (it.number == n) replacement else it }
        return Proto.encode { updated.forEach { field(it) } }
    }
}

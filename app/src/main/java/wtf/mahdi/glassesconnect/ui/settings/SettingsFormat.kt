package wtf.mahdi.glassesconnect.ui.settings

import wtf.mahdi.glassesconnect.protocol.GlassesSettings

/** Display helpers over [GlassesSettings] (labels stay in the protocol file; this only formats them). */
object SettingsFormat {
    fun group(field: Int) = GlassesSettings.groups.first { it.field == field }
    fun item(field: Int, vararg path: Int) = group(field).let { g -> g to g.items.first { it.path == path.toList() } }

    /** Glasses settings used on the Device tab. Fields/paths from GlassesSettings.groups. */
    val shootingMode get() = item(7, 1)
    val videoDuration get() = item(7, 2)
    val voiceWake get() = item(6, 1)

    /** Screen order and friendlier titles; unknown groups follow in protocol order. */
    private val order = listOf(7, 6, 8, 5, 9, 10, 1, 2, 3, 4)
    private val titles = mapOf(7 to "Camera", 8 to "Audio")
    val ordered get() = GlassesSettings.groups.sortedBy { order.indexOf(it.field).let { i -> if (i < 0) 99 else i } }
    fun title(g: GlassesSettings.Group) = titles[g.field] ?: g.title

    fun options(kind: GlassesSettings.Choice, current: Long?): List<Pair<Long, String>> =
        (kind.options.keys + listOfNotNull(current)).distinct().map { it to (kind.options[it]?.let(::pretty) ?: "Raw $it") }

    fun value(item: GlassesSettings.Item, v: Long?): String = when {
        v == null -> "Not set"
        item.kind is GlassesSettings.Choice -> (item.kind as GlassesSettings.Choice).options[v]?.let(::pretty) ?: "Raw $v"
        item.kind == GlassesSettings.Bool -> if (v == 1L) "On" else "Off"
        else -> v.toString()
    }

    /** "1200s (override)" -> "20 min (override)", "15s" -> "15 s". */
    fun pretty(label: String): String {
        val m = Regex("""^(\d+)s(.*)$""").find(label) ?: return label
        val sec = m.groupValues[1].toLong()
        val time = if (sec >= 60 && sec % 60 == 0L) "${sec / 60} min" else "$sec s"
        return time + m.groupValues[2]
    }

    fun readOnly(item: GlassesSettings.Item) = "read-only" in item.label
}

package wtf.mahdi.glassesconnect.ui

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavKey

sealed interface Route : NavKey {
    /** Bottom-bar destination; switching tabs resets the back stack to that tab. */
    sealed interface Tab : Route

    data object Device : Tab
    data object Gallery : Tab
    data object Ai : Tab
    data object More : Tab

    data class Viewer(val key: String) : Route
    data object Stream : Route
    data object Settings : Route
    data class Developer(val tab: Int = 0) : Route
    /** [first]: shown at launch without a key; finishing replaces the stack with [Device]. */
    data class Onboarding(val first: Boolean = false) : Route
}

class Navigator(private val stack: SnapshotStateList<NavKey>) {
    val top: NavKey get() = stack.last()

    fun go(route: Route) {
        if (route is Route.Tab) { stack.clear(); stack.add(route) } else stack.add(route)
    }

    fun back() {
        when {
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            top != Route.Device -> { stack.clear(); stack.add(Route.Device) }
        }
    }

    fun replaceAll(route: Route) { stack.clear(); stack.add(route) }
}

val LocalNav = staticCompositionLocalOf<Navigator> { error("No Navigator") }

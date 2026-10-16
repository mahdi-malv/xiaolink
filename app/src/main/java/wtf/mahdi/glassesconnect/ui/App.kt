package wtf.mahdi.glassesconnect.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.launch
import wtf.mahdi.glassesconnect.GlassesController
import wtf.mahdi.glassesconnect.R
import wtf.mahdi.glassesconnect.ui.ai.AiPhotoScreen
import wtf.mahdi.glassesconnect.ui.ai.AiScreen
import wtf.mahdi.glassesconnect.ui.ai.AiSettingsScreen
import wtf.mahdi.glassesconnect.ui.components.ProvideSnack
import wtf.mahdi.glassesconnect.ui.components.XIcon
import wtf.mahdi.glassesconnect.ui.developer.DeveloperScreen
import wtf.mahdi.glassesconnect.ui.device.DeviceScreen
import wtf.mahdi.glassesconnect.ui.gallery.GalleryScreen
import wtf.mahdi.glassesconnect.ui.gallery.ViewerScreen
import wtf.mahdi.glassesconnect.ui.more.MoreScreen
import wtf.mahdi.glassesconnect.ui.onboarding.OnboardingScreen
import wtf.mahdi.glassesconnect.ui.settings.SettingsScreen
import wtf.mahdi.glassesconnect.ui.stream.StreamScreen
import wtf.mahdi.glassesconnect.ui.theme.Xiao

private data class TabSpec(val route: Route.Tab, val label: String, val icon: Int, val iconOn: Int)

private val tabs = listOf(
    TabSpec(Route.Device, "Device", R.drawable.ic_eyeglasses, R.drawable.ic_eyeglasses_fill),
    TabSpec(Route.Gallery, "Gallery", R.drawable.ic_photo_library, R.drawable.ic_photo_library_fill),
    TabSpec(Route.Ai, "Gemini", R.drawable.ic_auto_awesome, R.drawable.ic_auto_awesome_fill),
    TabSpec(Route.More, "More", R.drawable.ic_menu, R.drawable.ic_menu),
)

@Composable
fun XiaoApp(controller: GlassesController) {
    val context = LocalContext.current
    setSingletonImageLoaderFactory { ctx -> ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build() }

    val start = controller.ui.value.let { if (it.token.isBlank() || it.address.isBlank()) Route.Onboarding(first = true) else Route.Device }
    val stack = remember { mutableStateListOf<NavKey>(start) }
    val nav = remember { Navigator(stack) }
    val snackHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showSnack: (String) -> Unit = { msg ->
        scope.launch { snackHost.currentSnackbarData?.dismiss(); snackHost.showSnackbar(msg) }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { controller.refreshPaired() }
    LaunchedEffect(Unit) { Permissions.missing(context).takeIf { it.isNotEmpty() }?.let(permissions::launch) }

    val top = stack.last()
    val ui by controller.ui.collectAsStateWithLifecycle()

    CompositionLocalProvider(LocalNav provides nav, LocalController provides controller) {
        ProvideSnack(showSnack) {
            Box(Modifier.fillMaxSize().background(Xiao.colors.bg)) {
                Column(Modifier.fillMaxSize()) {
                    NavDisplay(
                        backStack = stack,
                        modifier = Modifier.weight(1f),
                        onBack = { nav.back() },
                        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
                        popTransitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
                        entryProvider = entryProvider {
                            entry<Route.Device> { DeviceScreen() }
                            entry<Route.Gallery> { GalleryScreen() }
                            entry<Route.Ai> { AiScreen() }
                            entry<Route.More> { MoreScreen() }
                            entry<Route.Viewer> { ViewerScreen(it.key) }
                            entry<Route.AiPhoto> { AiPhotoScreen(it.path) }
                            entry<Route.AiSettings> { AiSettingsScreen() }
                            entry<Route.Stream> { StreamScreen() }
                            entry<Route.Settings> { SettingsScreen() }
                            entry<Route.Developer> { DeveloperScreen(it.tab) }
                            entry<Route.Onboarding> { OnboardingScreen(it.first) }
                        },
                    )
                    if (top is Route.Tab) BottomNav(top, newMedia = ui.pending.isNotEmpty(), onSelect = nav::go)
                }
                SnackbarHost(
                    snackHost,
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (top is Route.Tab) 80.dp else 12.dp),
                ) { data ->
                    Snackbar(data, shape = RoundedCornerShape(16.dp), containerColor = Xiao.colors.text, contentColor = Xiao.colors.bg,
                        modifier = Modifier.padding(horizontal = 14.dp))
                }
            }
            // A non-home tab goes back to Device before the app closes.
            BackHandler(enabled = stack.size == 1 && top is Route.Tab && top != Route.Device) { nav.back() }
        }
    }
}

@Composable
private fun BottomNav(current: Route.Tab, newMedia: Boolean, onSelect: (Route) -> Unit) {
    val c = Xiao.colors
    Column(Modifier.fillMaxWidth().background(c.nav)) {
        HorizontalDivider(color = c.line, thickness = 1.dp)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
            tabs.forEach { t ->
                val on = t.route == current
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Tab) { onSelect(t.route) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(Modifier.size(58.dp, 32.dp).clip(CircleShape).background(if (on) c.accentSoft else c.nav), contentAlignment = Alignment.Center) {
                        XIcon(if (on) t.iconOn else t.icon, tint = if (on) c.accent else c.muted)
                        if (t.route == Route.Gallery && newMedia) Box(Modifier.align(Alignment.TopEnd).offset((-10).dp, 3.dp).size(8.dp).clip(CircleShape).background(c.rec))
                    }
                    Text(t.label, style = MaterialTheme.typography.labelMedium, color = if (on) c.text else c.muted)
                }
            }
        }
    }
}

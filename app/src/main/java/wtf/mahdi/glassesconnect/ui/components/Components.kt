package wtf.mahdi.glassesconnect.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import wtf.mahdi.glassesconnect.R
import wtf.mahdi.glassesconnect.ui.theme.Xiao

/** Shows a short message in the app-wide snackbar. */
val LocalSnack = staticCompositionLocalOf<(String) -> Unit> { {} }

val CardShape = RoundedCornerShape(22.dp)

@Composable
fun XIcon(@DrawableRes id: Int, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, size: Dp = 22.dp, description: String? = null) =
    Icon(painterResource(id), description, modifier.size(size), tint = tint)

@Composable
fun IconBtn(@DrawableRes id: Int, description: String, onClick: () -> Unit, enabled: Boolean = true, tint: Color = LocalContentColor.current) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { XIcon(id, tint = if (enabled) tint else Xiao.colors.muted, description = description) }
}

/** Top bar. With [onBack] it is a sub-screen bar (back arrow, smaller title). */
@Composable
fun AppBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(start = if (onBack != null) 6.dp else 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconBtn(R.drawable.ic_arrow_back, "Back", onBack)
        Text(
            title, Modifier.weight(1f).padding(start = if (onBack != null) 4.dp else 0.dp),
            style = if (onBack != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

@Composable
fun XCard(modifier: Modifier = Modifier, shape: RoundedCornerShape = CardShape, content: @Composable ColumnScope.() -> Unit) {
    val c = Xiao.colors
    Column(modifier.clip(shape).background(c.surface).border(1.dp, c.line, shape), content = content)
}

class ListScope { val rows = mutableListOf<@Composable () -> Unit>(); fun row(content: @Composable () -> Unit) { rows += content } }

/** Card of rows with hairlines between them. */
@Composable
fun ListGroup(modifier: Modifier = Modifier, build: ListScope.() -> Unit) {
    val rows = ListScope().apply(build).rows
    if (rows.isEmpty()) return
    XCard(modifier.fillMaxWidth()) {
        rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(color = Xiao.colors.line, thickness = 1.dp)
            row()
        }
    }
}

@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    value: String? = null,
    monoSubtitle: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Xiao.colors
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 58.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
            XIcon(icon, size = 20.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) c.text else c.muted)
            if (!subtitle.isNullOrEmpty()) Text(
                subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                fontFamily = if (monoSubtitle) FontFamily.Monospace else null,
            )
        }
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = c.muted, maxLines = 1)
        if (trailing != null) trailing()
        else if (onClick != null) XIcon(R.drawable.ic_chevron_right, tint = c.muted, size = 20.dp)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, @DrawableRes icon: Int? = null, onChange: (Boolean) -> Unit) =
    ListRow(title, subtitle, icon = icon, enabled = enabled, onClick = { onChange(!checked) }) {
        val c = Xiao.colors
        Switch(
            checked, onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent, checkedTrackColor = c.accent, checkedBorderColor = c.accent,
                uncheckedThumbColor = c.muted, uncheckedTrackColor = c.surface2, uncheckedBorderColor = c.line,
            ),
        )
    }

/** Small uppercase label above a list group. */
@Composable
fun SectionLabel(text: String) = Text(
    text.uppercase(), Modifier.padding(start = 6.dp, top = 6.dp),
    style = MaterialTheme.typography.labelSmall, color = Xiao.colors.muted,
)

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        if (action != null) Text(
            action, Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(horizontal = 6.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge, color = Xiao.colors.accent,
        )
    }
}

@Composable
fun Pill(text: String, @DrawableRes icon: Int? = null, dot: Color? = null, fg: Color = Xiao.colors.text, bg: Color = Xiao.colors.surface2) {
    Row(
        Modifier.height(30.dp).clip(CircleShape).background(bg).padding(start = if (icon != null || dot != null) 9.dp else 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) Box(Modifier.padding(horizontal = 2.dp).size(7.dp).clip(CircleShape).background(dot))
        if (icon != null) XIcon(icon, tint = fg, size = 17.dp)
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

enum class Tone { Accent, Ghost, Stop }

@Composable
fun XButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    tone: Tone = Tone.Accent,
    big: Boolean = false,
) {
    val c = Xiao.colors
    val (bg, fg) = when (tone) {
        Tone.Accent -> c.accent to c.onAccent
        Tone.Ghost -> c.surface2 to c.text
        Tone.Stop -> c.rec to Color.White
    }
    val height = if (big) 54.dp else 40.dp
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = height), shape = CircleShape,
        color = if (enabled) bg else bg.copy(alpha = 0.45f), contentColor = if (enabled) fg else fg.copy(alpha = 0.7f),
    ) {
        Row(
            Modifier.padding(horizontal = if (big) 24.dp else 18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) { XIcon(icon, size = if (big) 22.dp else 19.dp); Spacer(Modifier.width(8.dp)) }
            Text(text, style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge)
        }
    }
}

/** Text link button. */
@Composable
fun XLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) = Text(
    text, modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
    style = MaterialTheme.typography.labelLarge, color = if (enabled) Xiao.colors.accent else Xiao.colors.muted,
)

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Xiao.colors
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (on) c.surface else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) c.text else c.muted, maxLines = 1)
            }
        }
    }
}

@Composable
fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Xiao.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Surface(
                onClick = { onSelect(i) }, shape = CircleShape, color = if (on) c.text else c.surface,
                contentColor = if (on) c.bg else c.text, border = if (on) null else BorderStroke(1.dp, c.line),
            ) { Text(label, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
fun XTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    password: Boolean = false,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
) {
    val c = Xiao.colors
    OutlinedTextField(
        value, onChange, modifier.fillMaxWidth(), enabled = enabled, singleLine = true, label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = c.muted) } },
        textStyle = MaterialTheme.typography.bodyLarge.let { if (mono) it.copy(fontFamily = FontFamily.Monospace) else it },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = c.surface, unfocusedContainerColor = c.surface, disabledContainerColor = c.surface,
            focusedBorderColor = c.accent, unfocusedBorderColor = c.line, focusedLabelColor = c.accent, unfocusedLabelColor = c.muted,
            cursorColor = c.accent,
        ),
    )
}

/** Bottom sheet with a title and free content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XSheet(onDismiss: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Xiao.colors
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface,
        contentColor = c.text, dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(CircleShape).background(c.line)) }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Single-choice sheet; [tags] mark options with a warning note (e.g. "override"). */
@Composable
fun ChoiceSheet(title: String, options: List<Pair<Long, String>>, selected: Long?, onPick: (Long) -> Unit, onDismiss: () -> Unit) =
    XSheet(onDismiss, title) {
        val c = Xiao.colors
        Column {
            options.forEach { (v, label) ->
                val on = v == selected
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.RadioButton) { onPick(v) }.padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    XIcon(if (on) R.drawable.ic_radio_button_checked_fill else R.drawable.ic_radio_button_unchecked, tint = if (on) c.accent else c.muted)
                    val (main, note) = splitNote(label)
                    Text(main, style = MaterialTheme.typography.bodyLarge)
                    if (note != null) Text(note, style = MaterialTheme.typography.labelMedium, color = c.warn)
                }
            }
        }
    }

/** "Video duration (override)" -> ("Video duration", "override"). */
fun splitNote(label: String): Pair<String, String?> {
    val i = label.indexOf(" (")
    return if (i > 0 && label.endsWith(")")) label.substring(0, i) to label.substring(i + 2, label.length - 1) else label to null
}

/** Standard vertical screen padding + spacing for scrolling content. */
val ScreenPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)

@Composable
fun ProvideSnack(show: (String) -> Unit, content: @Composable () -> Unit) = CompositionLocalProvider(LocalSnack provides show, content = content)

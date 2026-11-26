package com.projectx.ui.compose.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.projectx.script.ActionableConfig
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigItem
import com.projectx.script.ConfigSection
import com.projectx.script.ConfigVisibilityProvider
import com.projectx.script.ConfigurableScript
import com.projectx.script.EnumConfigItem
import com.projectx.script.InfoDisplayConfigItem
import com.projectx.script.IntConfigItem
import com.projectx.script.MultiSelectConfigItem
import com.projectx.mcp.MainLogicTickQueue
import com.projectx.script.configItems
import com.projectx.script.OptionsConfigItem
import com.projectx.script.ScriptConfigStore
import com.projectx.script.ScriptExecutor
import com.projectx.script.ScriptMetadata
import com.projectx.script.StringConfigItem
import com.projectx.ui.compose.OverlayClock
import com.projectx.ui.compose.OverlayKeyboard
import com.projectx.ui.compose.OverlayText
import com.projectx.ui.compose.components.ActionButton
import com.projectx.ui.compose.components.Divider
import com.projectx.ui.compose.components.Dropdown
import com.projectx.ui.compose.components.ToggleChip
import com.projectx.ui.compose.components.Glyph
import com.projectx.ui.compose.components.GlyphIcon
import com.projectx.ui.compose.components.Hint
import com.projectx.ui.compose.components.SearchField
import com.projectx.ui.compose.components.NumberInput
import com.projectx.ui.compose.components.SettingRow
import com.projectx.ui.compose.components.TextInput
import com.projectx.ui.compose.components.Toggle
import com.projectx.ui.compose.components.press
import com.projectx.ui.compose.components.rememberHover
import com.projectx.ui.compose.theme.LocalType
import com.projectx.ui.compose.theme.Palette

/**
 * A script's own settings, edited in place.
 *
 * A running script is edited live. One that is not running gets an instance built for editing, loaded from and saved
 * to the config store, which is where starting it reads from - so there is no separate save step to forget.
 */
object ScriptSettings {
    private val drafts = mutableMapOf<String, ConfigurableScript>()

    /** Config items are plain fields Compose cannot watch, so every edit bumps this and the settings redraw. */
    var revision by mutableIntStateOf(0)
        private set

    fun instanceFor(meta: ScriptMetadata): ConfigurableScript? {
        (ScriptExecutor.getScriptInstance(meta.scriptClass) as? ConfigurableScript)?.let { return it }
        return drafts.getOrPut(meta.scriptClass.name) {
            val created = runCatching { meta.scriptClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance() }.getOrNull() as? ConfigurableScript
                ?: return null
            ScriptConfigStore.applyTo(created)
            created
        }
    }

    fun items(script: ConfigurableScript): List<Pair<String, ConfigItem<*>>> {
        val visibility = script as? ConfigVisibilityProvider
        return configItems(script)
            .map { it.legacyKey to it.item }
            .filter { (name, item) -> visibility?.isConfigItemVisible(name, item) ?: true }
    }

    fun changed(script: ConfigurableScript) {
        ScriptConfigStore.save(script)
        runCatching { script.javaClass.getMethod("onConfigUpdated").invoke(script) }
            .onFailure { if (it !is NoSuchMethodException) println("[Overlay] onConfigUpdated failed: ${it.message}") }
        revision++
    }

    /** Back to what the script itself declares, read off a fresh instance rather than guessed per type. */
    fun resetToDefaults(script: ConfigurableScript) {
        val fresh = runCatching { script.javaClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance() }.getOrNull() ?: return
        val defaults = configItems(fresh).associateBy { it.key }
        configItems(script).forEach { field ->
            @Suppress("UNCHECKED_CAST")
            val target = field.item as? ConfigItem<Any?> ?: return@forEach
            val source = defaults[field.key]?.item ?: return@forEach
            if (target !is ConfigSection && target !is InfoDisplayConfigItem) target.value = source.value
        }
        changed(script)
    }

    fun forget() = drafts.clear()
}

@Composable
fun ScriptSettingsView(meta: ScriptMetadata) {
    val script = remember(meta.scriptClass.name) { ScriptSettings.instanceFor(meta) }
    if (script == null) {
        Hint("This script's settings could not be loaded.")
        return
    }
    // Read so the view redraws after an edit, and once a second for values a running script reports.
    ScriptSettings.revision
    OverlayClock.nowSeconds

    val items = ScriptSettings.items(script)
    val collapsed = remember(meta.scriptClass.name) { mutableStateMapOf<String, Boolean>() }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (items.none { it.second !is ConfigSection }) Hint("This script has nothing to set.")
        var sectionOpen = true
        items.forEach { (name, item) ->
            if (item !is ConfigSection && !sectionOpen) return@forEach
            when (item) {
                is ConfigSection -> {
                    sectionOpen = !(collapsed[item.name] ?: !item.defaultOpen)
                    val open = sectionOpen
                    SectionHeader(item.name, open) { collapsed[item.name] = open }
                }
                is InfoDisplayConfigItem -> SettingRow(item.name, item.description) {
                    InfoValue(item)
                    ItemAction(script, item)
                }
                is MultiSelectConfigItem -> MultiSelectSetting(script, item)
                else -> SettingRow(item.name, item.description) {
                    ItemControl(script, name, item)
                    ItemAction(script, item)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hint("Changes save as you make them.")
            Spacer(Modifier.weight(1f))
            ActionButton("Reset to defaults", { ScriptSettings.resetToDefaults(script) }, height = 30.dp)
        }
    }
}

@Suppress("UNCHECKED_CAST")
@Composable
private fun ItemControl(script: ConfigurableScript, fieldName: String, item: ConfigItem<*>) {
    // The same script and item arrive on every redraw, so Compose would skip this; reading the revision here is
    // what makes an edit (a step button, a toggle, a reset) show up.
    ScriptSettings.revision
    fun set(value: Any?) {
        (item as ConfigItem<Any?>).value = value
        ScriptSettings.changed(script)
    }
    when (item) {
        is BooleanConfigItem -> Toggle(item.value) { set(it) }
        is IntConfigItem -> NumberInput(item.value, { set(it) }, item.min..item.max, width = 150.dp)
        is StringConfigItem -> {
            // The setting is a plain field Compose cannot watch, so the field shows a state copy that typing updates.
            var shown by remember(script, fieldName) { mutableStateOf(item.value) }
            val field = remember(script, fieldName) {
                OverlayText(
                    read = { shown },
                    write = { shown = it; item.value = it },
                    maxLength = 256,
                    onDone = { ScriptSettings.changed(script) },
                )
            }
            LaunchedEffect(ScriptSettings.revision) {
                if (OverlayKeyboard.focused !== field) shown = item.value
            }
            TextInput(field, "", 220.dp)
        }
        is EnumConfigItem<*> -> Dropdown(item.value, item.enumValues.toList(), { set(it) }, width = 220.dp)
        is OptionsConfigItem<*> -> Dropdown(item.value, item.options.toList(), { set(it) }, width = 220.dp) { it?.toString() ?: "None" }
        else -> BasicText("${item.value}", style = LocalType.current.data)
    }
}

private const val SEARCH_FROM_OPTIONS = 10

/**
 * A tick list laid out under its label rather than squeezed beside it: the chips wrap across the row, and a long list
 * gets a search box and a capped height that scrolls on its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiSelectSetting(script: ConfigurableScript, item: MultiSelectConfigItem) {
    // Compose skips this for the same item on every redraw: the revision shows a tick, the clock a scan that finished
    // on the game thread.
    ScriptSettings.revision
    OverlayClock.nowSeconds
    fun set(value: Set<String>) {
        item.value = value
        ScriptSettings.changed(script)
    }
    val shown = item.shownOptions
    var query by remember(script, item.name) { mutableStateOf("") }
    val search = remember(script, item.name) { OverlayText(read = { query }, write = { query = it }, maxLength = 40) }
    val visible = if (query.isBlank()) shown else shown.filter { it.contains(query.trim(), ignoreCase = true) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingRow(item.name, item.description) {
            if (shown.isNotEmpty()) {
                BasicText("${item.value.size} of ${shown.size} selected", style = LocalType.current.data.copy(color = Palette.muted))
            }
            if (item.value.isNotEmpty()) ActionButton("Clear", { set(emptySet()) }, height = 32.dp)
            ItemAction(script, item)
        }
        when {
            shown.isEmpty() -> Hint(if (item.scan != null) "Nothing listed yet: press ${item.scan?.label}." else "Nothing to choose from.")
            else -> {
                if (shown.size > SEARCH_FROM_OPTIONS) SearchField(search, "Search ${item.name.lowercase()}", 260.dp)
                if (visible.isEmpty()) Hint("Nothing matches \"${query.trim()}\".")
                Box(Modifier.fillMaxWidth().heightIn(max = 168.dp).verticalScroll(rememberScrollState())) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        visible.forEach { option ->
                            ToggleChip(option, item.isSelected(option)) { ticked ->
                                set(if (ticked) item.value + option else item.value.filterNot { it.equals(option, ignoreCase = true) }.toSet())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
@Composable
private fun ItemAction(script: ConfigurableScript, item: ConfigItem<*>) {
    if (item is MultiSelectConfigItem) {
        val scan = item.scan ?: return
        ActionButton(scan.label, {
            onGameThread(item.name) {
                item.rescan()
                ScriptSettings.changed(script)
            }
        }, height = 32.dp)
        return
    }
    val action = (item as? ActionableConfig<Any?>)?.action ?: return
    ActionButton(action.label, {
        onGameThread(item.name) {
            action.run()?.let { produced ->
                (item as ConfigItem<Any?>).value = produced
                ScriptSettings.changed(script)
            }
        }
    }, height = 32.dp)
}

/**
 * Runs a settings button's work on the game thread. Buttons read the game (a scan of nearby NPCs, the player's tile),
 * and the client changes those tables mid-frame without locks, so a read from the overlay thread can see them half
 * built and silently come back empty.
 */
private fun onGameThread(setting: String, work: () -> Unit) {
    val queued = MainLogicTickQueue.enqueue {
        runCatching(work).onFailure { println("[Settings] $setting: ${it.message ?: it}") }
    }
    if (!queued) println("[Settings] $setting: the game is busy; press it again")
}

@Composable
private fun SectionHeader(name: String, open: Boolean, onToggle: () -> Unit) {
    val hover = rememberHover()
    Column(Modifier.padding(top = 6.dp).press(hover, onToggle), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GlyphIcon(if (open) Glyph.ChevronDown else Glyph.ChevronRight, if (hover.hovered) Palette.text else Palette.muted, 10.dp)
            BasicText(name.uppercase(), style = LocalType.current.eyebrow)
        }
        Divider()
    }
}

@Composable
private fun InfoValue(item: InfoDisplayConfigItem) {
    ScriptSettings.revision
    OverlayClock.nowSeconds
    BasicText(item.value.ifEmpty { "-" }, style = LocalType.current.data.copy(color = Palette.text))
}

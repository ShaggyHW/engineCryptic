package com.projectx.ui.backend.dsl

import com.projectx.ui.backend.dsl.scopes.*
import com.projectx.ui.backend.dsl.boolState as newBoolState
import com.projectx.ui.backend.dsl.floatState as newFloatState
import com.projectx.ui.backend.dsl.intState as newIntState
import com.projectx.ui.backend.dsl.stringState as newStringState
import com.projectx.ui.backend.dsl.utils.ImGuiCol
import com.projectx.ui.backend.dsl.utils.ImGuiColorEditFlags
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.backend.dsl.utils.ImGuiDir
import com.projectx.ui.backend.dsl.utils.ImGuiMouseButton
import com.projectx.ui.backend.dsl.utils.ImGuiSliderFlags
import com.projectx.ui.backend.dsl.utils.ImGuiStyleVar
import com.projectx.ui.backend.flags.ImGuiCond
import com.projectx.ui.backend.flags.InputTextFlags
import com.projectx.ui.backend.flags.PopupFlags
import com.projectx.ui.backend.flags.WindowFlags
import com.projectx.ui.backend.native.ImGuiTexture
import com.projectx.ui.backend.native.graphicTexture as cacheGraphicTexture
import org.projectx.core.game.skill.Skill
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.IntConsumer
import java.util.function.Supplier

/**
 * The overlay DSL for Java scripts: `import static com.projectx.ui.backend.dsl.Overlay.*;` and call these from
 * `render()`.
 *
 * Kotlin builds overlay windows with lambdas that have a receiver and return Unit, which Java can only write by
 * returning `Unit.INSTANCE`, and window flags are a value class Java cannot pass. Each builder here takes a
 * [Consumer] of the scope instead, and flags as a plain int. Kotlin scripts keep using [ImGuiDsl] and the scope
 * extensions directly.
 *
 * ```java
 * @Override
 * public void render() {
 *     window("My Script", w -> {
 *         text(w, "Status: " + status);
 *         button(w, "Stop", this::stop);
 *     });
 * }
 * ```
 */
object Overlay {

    // ---- Window flags and conditions as plain ints; OR flags together ----

    @JvmField val WINDOW_NO_TITLE_BAR = WindowFlags.NoTitleBar.value
    @JvmField val WINDOW_NO_RESIZE = WindowFlags.NoResize.value
    @JvmField val WINDOW_NO_MOVE = WindowFlags.NoMove.value
    @JvmField val WINDOW_NO_SCROLLBAR = WindowFlags.NoScrollbar.value
    @JvmField val WINDOW_NO_COLLAPSE = WindowFlags.NoCollapse.value
    @JvmField val WINDOW_ALWAYS_AUTO_RESIZE = WindowFlags.AlwaysAutoResize.value
    @JvmField val WINDOW_NO_BACKGROUND = WindowFlags.NoBackground.value
    @JvmField val WINDOW_NO_SAVED_SETTINGS = WindowFlags.NoSavedSettings.value
    @JvmField val WINDOW_MENU_BAR = WindowFlags.MenuBar.value
    @JvmField val WINDOW_NO_DECORATION = WindowFlags.NoDecoration.value

    @JvmField val COND_ALWAYS = ImGuiCond.Always.value
    @JvmField val COND_ONCE = ImGuiCond.Once.value
    @JvmField val COND_FIRST_USE_EVER = ImGuiCond.FirstUseEver.value
    @JvmField val COND_APPEARING = ImGuiCond.Appearing.value

    @JvmField val INPUT_CHARS_DECIMAL = InputTextFlags.CharsDecimal.value
    @JvmField val INPUT_CHARS_HEXADECIMAL = InputTextFlags.CharsHexadecimal.value
    @JvmField val INPUT_CHARS_UPPERCASE = InputTextFlags.CharsUppercase.value
    @JvmField val INPUT_CHARS_NO_BLANK = InputTextFlags.CharsNoBlank.value
    @JvmField val INPUT_ENTER_RETURNS_TRUE = InputTextFlags.EnterReturnsTrue.value
    @JvmField val INPUT_AUTO_SELECT_ALL = InputTextFlags.AutoSelectAll.value
    @JvmField val INPUT_READ_ONLY = InputTextFlags.ReadOnly.value
    @JvmField val INPUT_PASSWORD = InputTextFlags.Password.value

    @JvmField val POPUP_MOUSE_BUTTON_RIGHT = PopupFlags.MouseButtonRight.value
    @JvmField val POPUP_NO_REOPEN = PopupFlags.NoReopen.value
    @JvmField val POPUP_NO_OPEN_OVER_EXISTING_POPUP = PopupFlags.NoOpenOverExistingPopup.value

    @JvmField val COLOR_NO_ALPHA = ImGuiColorEditFlags.NoAlpha
    @JvmField val COLOR_NO_PICKER = ImGuiColorEditFlags.NoPicker
    @JvmField val COLOR_NO_OPTIONS = ImGuiColorEditFlags.NoOptions
    @JvmField val COLOR_NO_INPUTS = ImGuiColorEditFlags.NoInputs
    @JvmField val COLOR_NO_TOOLTIP = ImGuiColorEditFlags.NoTooltip
    @JvmField val COLOR_NO_LABEL = ImGuiColorEditFlags.NoLabel
    @JvmField val COLOR_NO_SIDE_PREVIEW = ImGuiColorEditFlags.NoSidePreview
    @JvmField val COLOR_ALPHA_BAR = ImGuiColorEditFlags.AlphaBar

    @JvmField val SLIDER_LOGARITHMIC = ImGuiSliderFlags.Logarithmic
    @JvmField val SLIDER_NO_ROUND_TO_FORMAT = ImGuiSliderFlags.NoRoundToFormat
    @JvmField val SLIDER_NO_INPUT = ImGuiSliderFlags.NoInput
    @JvmField val SLIDER_WRAP_AROUND = ImGuiSliderFlags.WrapAround
    @JvmField val SLIDER_ALWAYS_CLAMP = ImGuiSliderFlags.AlwaysClamp

    @JvmField val DIR_LEFT = ImGuiDir.Left
    @JvmField val DIR_RIGHT = ImGuiDir.Right
    @JvmField val DIR_UP = ImGuiDir.Up
    @JvmField val DIR_DOWN = ImGuiDir.Down

    @JvmField val MOUSE_LEFT = ImGuiMouseButton.Left
    @JvmField val MOUSE_RIGHT = ImGuiMouseButton.Right
    @JvmField val MOUSE_MIDDLE = ImGuiMouseButton.Middle

    // ---- Windows ----

    /** A window titled [title] whose content [content] adds. */
    @JvmStatic
    fun window(title: String, content: Consumer<WindowScope>) = window(title, 0, content)

    /** A window titled [title] with ImGui window [flags] (WindowFlags values ORed together). */
    @JvmStatic
    fun window(title: String, flags: Int, content: Consumer<WindowScope>) =
        ImGuiDsl.window(title, WindowFlags(flags)) { content.accept(this) }

    /** A window with a close button, which sets [open] false; nothing is drawn while it is false. */
    @JvmStatic
    fun window(title: String, flags: Int, open: ImGuiState<Boolean>, content: Consumer<WindowScope>) =
        ImGuiDsl.window(title, WindowFlags(flags), open) { content.accept(this) }

    /** Positions the next window so the point ([pivotX], [pivotY]) of it, 0..1 across, sits at ([x], [y]). */
    @JvmStatic
    fun setNextWindowPos(x: Float, y: Float, cond: Int, pivotX: Float, pivotY: Float) =
        ImGuiDsl.setNextWindowPos(x, y, ImGuiCond(cond), pivotX, pivotY)

    /** [setNextWindowPos] without keeping the window on screen. */
    @JvmStatic
    fun setNextWindowPosUnsafe(x: Float, y: Float, cond: Int, pivotX: Float, pivotY: Float) =
        ImGuiDsl.setNextWindowPosUnsafe(x, y, ImGuiCond(cond), pivotX, pivotY)

    /** [setNextWindowSize] without clamping the window to the screen. */
    @JvmStatic
    fun setNextWindowSizeUnsafe(width: Float, height: Float, cond: Int) =
        ImGuiDsl.setNextWindowSizeUnsafe(width, height, ImGuiCond(cond))

    /** Positions the next window at ([x], [y]) on screen, every frame. */
    @JvmStatic
    fun setNextWindowPos(x: Float, y: Float) = ImGuiDsl.setNextWindowPos(x, y)

    /** Positions the next window at ([x], [y]) under an ImGuiCond [cond], such as first use only. */
    @JvmStatic
    fun setNextWindowPos(x: Float, y: Float, cond: Int) = ImGuiDsl.setNextWindowPos(x, y, ImGuiCond(cond))

    /** Sizes the next window, every frame. */
    @JvmStatic
    fun setNextWindowSize(width: Float, height: Float) = ImGuiDsl.setNextWindowSize(width, height)

    /** Sizes the next window under an ImGuiCond [cond], such as first use only. */
    @JvmStatic
    fun setNextWindowSize(width: Float, height: Float, cond: Int) = ImGuiDsl.setNextWindowSize(width, height, ImGuiCond(cond))

    /** Draws straight onto the game view, behind every window: tile highlights, text over the world, shapes. */
    @JvmStatic
    fun backgroundDrawList(content: Consumer<BackgroundDrawListScope>) = ImGuiDsl.backgroundDrawList { content.accept(this) }

    // ---- State that survives between frames ----

    /** A value kept under [key] across frames, created by [factory] the first time. */
    @JvmStatic
    fun <T> persistentState(key: String, factory: Supplier<ImGuiState<T>>): ImGuiState<T> =
        ImGuiDsl.persistentState(key) { factory.get() }

    @JvmStatic
    fun boolState(initialValue: Boolean): ImGuiState<Boolean> = newBoolState(initialValue)

    @JvmStatic
    fun intState(initialValue: Int): ImGuiState<Int> = newIntState(initialValue)

    @JvmStatic
    fun floatState(initialValue: Float): ImGuiState<Float> = newFloatState(initialValue)

    @JvmStatic
    fun stringState(initialValue: String): ImGuiState<String> = newStringState(initialValue)

    /** A set of selected indices, for [multiSelectListBox]. */
    @JvmStatic
    fun indexSetState(initialValue: Set<Int>): ImGuiState<Set<Int>> = IndexSetState(initialValue)

    // ---- Text and layout ----

    @JvmStatic
    fun text(scope: LayoutScope, text: String) = scope.text(text)

    @JvmStatic
    fun textWrapped(scope: LayoutScope, text: String) = scope.textWrapped(text)

    /** A titled group: accent text over a separator. */
    @JvmStatic
    fun section(scope: LayoutScope, title: String) = scope.section(title)

    @JvmStatic
    fun separator(scope: LayoutScope) = scope.separator()

    @JvmStatic
    fun sameLine(scope: LayoutScope) = scope.sameLine()

    @JvmStatic
    fun spacing(scope: LayoutScope) = scope.spacing()

    @JvmStatic
    fun newLine(scope: LayoutScope) = scope.newLine()

    /** A tooltip on the item added just before this. */
    @JvmStatic
    fun itemTooltip(scope: LayoutScope, text: String) = scope.itemTooltip(text)

    @JvmStatic
    fun progressBar(scope: LayoutScope, fraction: Float) = scope.progressBar(fraction)

    @JvmStatic
    fun progressBar(scope: LayoutScope, fraction: Float, overlayText: String) = scope.progressBar(fraction, overlay = overlayText)

    /** The player's level in [skill] and progress to the next. */
    @JvmStatic
    fun xpProgressBar(scope: LayoutScope, skill: Skill) = scope.xpProgressBar(skill)

    @JvmStatic
    fun image(scope: LayoutScope, texture: ImGuiTexture, width: Float, height: Float) = scope.image(texture, width, height)

    /** The cache graphic [id] as a texture; Java cannot import the texture type, so hold it in a `var`. */
    @JvmStatic
    fun graphicTexture(id: Int): ImGuiTexture = cacheGraphicTexture(id)

    /** An image file or classpath resource as a texture, or null if it cannot be loaded. */
    @JvmStatic
    fun textureFromPath(pathOrResource: String): ImGuiTexture? = ImGuiTexture.fromPath(pathOrResource)

    // ---- Controls ----

    @JvmStatic
    fun button(scope: LayoutScope, label: String, onClick: Runnable) = scope.button(label) { onClick.run() }

    @JvmStatic
    fun button(scope: LayoutScope, label: String, width: Float, height: Float, onClick: Runnable) =
        scope.button(label, width, height) { onClick.run() }

    @JvmStatic
    fun smallButton(scope: LayoutScope, label: String, onClick: Runnable) = scope.smallButton(label) { onClick.run() }

    /** A checkbox showing [checked]; [onChange] gets the new value when the user clicks it. */
    @JvmStatic
    fun checkbox(scope: LayoutScope, label: String, checked: Boolean, onChange: Consumer<Boolean>) =
        scope.checkbox(label, checked) { onChange.accept(it) }

    /** A checkbox bound to [state]. */
    @JvmStatic
    fun checkbox(scope: LayoutScope, label: String, state: ImGuiState<Boolean>) = scope.checkbox(label, state)

    /** A text field showing [value]; [onChange] gets the new text as the user types. */
    @JvmStatic
    fun inputText(scope: LayoutScope, label: String, value: String, onChange: Consumer<String>) =
        scope.inputText(label, value) { onChange.accept(it) }

    /** A text field of up to [maxLength] characters with INPUT_ [flags] ORed together. */
    @JvmStatic
    fun inputText(scope: LayoutScope, label: String, value: String, maxLength: Int, flags: Int, onChange: Consumer<String>) =
        scope.inputText(label, value, maxLength, InputTextFlags(flags)) { onChange.accept(it) }

    /** A text field bound to [state], with INPUT_ [flags] ORed together. */
    @JvmStatic
    fun inputText(scope: LayoutScope, label: String, state: ImGuiState<String>, flags: Int) =
        scope.inputText(label, state, InputTextFlags(flags))

    /** A text box [width] by [height] (0 to fit) showing [value]; [onChange] gets the new text. */
    @JvmStatic
    fun inputTextMultiline(scope: LayoutScope, label: String, value: String, width: Float, height: Float, onChange: Consumer<String>) =
        scope.inputTextMultiline(label, value, sizeX = width, sizeY = height) { onChange.accept(it) }

    /** A text box bound to [state], [width] by [height] (0 to fit), with INPUT_ [flags] ORed together. */
    @JvmStatic
    fun inputTextMultiline(scope: LayoutScope, label: String, state: ImGuiState<String>, width: Float, height: Float, flags: Int) =
        scope.inputTextMultiline(label, state, width, height, InputTextFlags(flags))

    /** Two number fields on one line, bound to [x] and [y]. */
    @JvmStatic
    fun inputFloat2(scope: LayoutScope, label: String, x: ImGuiState<Float>, y: ImGuiState<Float>, format: String) =
        scope.inputFloat2(label, x, y, format)

    @JvmStatic
    fun inputFloat3(scope: LayoutScope, label: String, x: ImGuiState<Float>, y: ImGuiState<Float>, z: ImGuiState<Float>, format: String) =
        scope.inputFloat3(label, x, y, z, format)

    @JvmStatic
    fun inputFloat4(
        scope: LayoutScope,
        label: String,
        x: ImGuiState<Float>,
        y: ImGuiState<Float>,
        z: ImGuiState<Float>,
        w: ImGuiState<Float>,
        format: String,
    ) = scope.inputFloat4(label, x, y, z, w, format)

    /** A number field showing [value]; [onChange] gets the new number. */
    @JvmStatic
    fun inputInt(scope: LayoutScope, label: String, value: Int, onChange: IntConsumer) =
        scope.inputInt(label, value) { onChange.accept(it) }

    @JvmStatic
    fun sliderInt(scope: LayoutScope, label: String, state: ImGuiState<Int>, min: Int, max: Int) = scope.sliderInt(label, state, min, max)

    @JvmStatic
    fun sliderFloat(scope: LayoutScope, label: String, state: ImGuiState<Float>, min: Float, max: Float) =
        scope.sliderFloat(label, state, min, max)

    /** A dropdown of [items] showing [selectedIndex]; [onChange] gets the index the user picks. */
    @JvmStatic
    fun combo(scope: LayoutScope, label: String, selectedIndex: Int, items: List<String>, onChange: IntConsumer) =
        scope.combo(label, selectedIndex, items) { onChange.accept(it) }

    /** A dropdown showing [preview] whose entries [content] adds; true while it is open. */
    @JvmStatic
    fun combo(scope: LayoutScope, label: String, preview: String, content: Consumer<ComboScope>): Boolean =
        scope.combo(label, preview) { content.accept(this) }

    @JvmStatic
    fun selectable(scope: LayoutScope, label: String, selected: Boolean, onClick: Runnable) =
        scope.selectable(label, selected) { onClick.run() }

    // ---- Containers ----

    /** Lays [content] out as one item, so [sameLine] treats it as a block. */
    @JvmStatic
    fun group(scope: LayoutScope, content: Consumer<ChildScope>) = scope.group { content.accept(this) }

    /** A scrolling region [width] by [height] (0 fills the space). */
    @JvmStatic
    fun child(scope: LayoutScope, id: String, width: Float, height: Float, content: Consumer<ChildScope>) =
        scope.child(id, width, height) { content.accept(this) }

    /** A header the user can fold away, with [content] indented under it. */
    @JvmStatic
    fun collapsingHeader(scope: LayoutScope, label: String, content: Consumer<ChildScope>) =
        scope.collapsingHeader(label) { content.accept(this) }

    /** An arrow and label whose [content] shows while open. */
    @JvmStatic
    fun treeNode(scope: LayoutScope, label: String, content: Consumer<ChildScope>) = scope.treeNode(label) { content.accept(this) }

    /** A table of [columns] columns; add rows with `nextRow` and cells with `nextColumn` on the table. */
    @JvmStatic
    fun table(scope: LayoutScope, id: String, columns: Int, content: Consumer<TableScope>): Boolean =
        scope.table(id, columns) { content.accept(this) }

    /** A two-column label and control grid; fill it with [row] and [valueRow]. */
    @JvmStatic
    fun properties(scope: LayoutScope, id: String, content: Consumer<TableScope>) = scope.properties(id) { content.accept(this) }

    /** One labelled row of a [properties] grid; [control] fills the value column. */
    @JvmStatic
    fun row(table: TableScope, label: String, control: Consumer<TableScope>) = table.row(label) { control.accept(this) }

    /** A read-only label and value row of a [properties] grid. */
    @JvmStatic
    fun valueRow(table: TableScope, label: String, value: String) = table.valueRow(label, value)

    /** A row of tabs; add each with [tabItem]. */
    @JvmStatic
    fun tabBar(scope: LayoutScope, id: String, content: Consumer<TabBarScope>): Boolean = scope.tabBar(id) { content.accept(this) }

    /** A tab whose [content] shows while it is selected; true while selected. */
    @JvmStatic
    fun tabItem(tabBar: TabBarScope, label: String, content: Consumer<ChildScope>): Boolean =
        tabBar.tabItem(label) { content.accept(this) }

    /** A list box [width] by [height] whose entries [content] adds. */
    @JvmStatic
    fun listBox(scope: LayoutScope, label: String, width: Float, height: Float, content: Consumer<ListBoxScope>): Boolean =
        scope.listBox(label, width, height) { content.accept(this) }

    /** [content] drawn with ImGui colour [colorIndex] set to [color]. */
    @JvmStatic
    fun styleColor(scope: LayoutScope, colorIndex: ImGuiCol, color: Int, content: Consumer<ChildScope>) =
        scope.styleColor(colorIndex, color) { content.accept(this) }

    /** [content] with controls [width] pixels wide. */
    @JvmStatic
    fun itemWidth(scope: LayoutScope, width: Float, content: Consumer<ChildScope>) = scope.itemWidth(width) { content.accept(this) }

    // ---- Popups ----

    /** Opens the popup [id] on the next frame, with POPUP_ [flags] ORed together. */
    @JvmStatic
    @JvmOverloads
    fun openPopup(window: WindowScope, id: String, flags: Int = 0) = window.openPopup(id, PopupFlags(flags))

    /** The popup [id], drawn by [content] while it is open; true while open. */
    @JvmStatic
    @JvmOverloads
    fun popup(window: WindowScope, id: String, flags: Int = 0, content: Consumer<PopupScope>): Boolean =
        window.popup(id, flags) { content.accept(this) }

    /** A modal popup [name] that blocks the rest of the overlay while open; true while open. */
    @JvmStatic
    @JvmOverloads
    fun popupModal(window: WindowScope, name: String, flags: Int = 0, content: Consumer<ModalScope>): Boolean =
        window.popupModal(name, null, WindowFlags(flags)) { content.accept(this) }

    /** [popupModal] with a close button, which sets [open] false. */
    @JvmStatic
    fun popupModal(window: WindowScope, name: String, open: ImGuiState<Boolean>, flags: Int, content: Consumer<ModalScope>): Boolean =
        window.popupModal(name, open, WindowFlags(flags)) { content.accept(this) }

    // ---- Dialogs ----

    /** A modal asking [message] with OK and Cancel; shown while [open] is true. */
    @JvmStatic
    @JvmOverloads
    fun confirmationDialog(
        window: WindowScope,
        title: String,
        message: String,
        open: ImGuiState<Boolean>,
        onConfirm: Runnable = Runnable {},
        onCancel: Runnable = Runnable {},
    ): Boolean = window.confirmationDialog(title, message, open, { onConfirm.run() }, { onCancel.run() })

    /** A modal with a text field bound to [text]; [onOk] gets the text when the user presses OK. */
    @JvmStatic
    @JvmOverloads
    fun inputDialog(
        window: WindowScope,
        title: String,
        label: String,
        text: ImGuiState<String>,
        open: ImGuiState<Boolean>,
        onOk: Consumer<String> = Consumer {},
        onCancel: Runnable = Runnable {},
    ): Boolean = window.inputDialog(title, label, text, open, { onOk.accept(it) }, { onCancel.run() })

    /** A modal with one button per entry of [choices]; [onChoice] gets the index and text picked. */
    @JvmStatic
    fun choiceDialog(
        window: WindowScope,
        title: String,
        message: String,
        open: ImGuiState<Boolean>,
        choices: List<String>,
        onChoice: BiConsumer<Int, String>,
    ): Boolean = window.choiceDialog(title, message, open, choices) { index, choice -> onChoice.accept(index, choice) }

    // ---- Menus ----

    /** The menu bar of a window opened with WINDOW_MENU_BAR; add menus to its scope with [menu]. */
    @JvmStatic
    fun menuBar(window: WindowScope, content: Consumer<MenuScope>): Boolean = window.menuBar { content.accept(this) }

    /** A menu whose entries [content] adds; true while it is open. */
    @JvmStatic
    @JvmOverloads
    fun menu(window: WindowScope, label: String, enabled: Boolean = true, content: Consumer<MenuScope>): Boolean =
        window.menu(label, enabled) { content.accept(this) }

    /** A submenu of [menu]. */
    @JvmStatic
    fun menu(menu: MenuScope, label: String, content: Consumer<MenuScope>): Boolean = menu.menu(label) { content.accept(this) }

    /** A submenu of a context menu. */
    @JvmStatic
    fun menu(menu: ContextMenuScope, label: String, content: Consumer<ContextMenuScope>): Boolean =
        menu.menu(label) { content.accept(this) }

    /** A menu entry showing [shortcut] beside it and a tick while [selected]. */
    @JvmStatic
    @JvmOverloads
    fun menuItem(menu: MenuScope, label: String, shortcut: String = "", selected: Boolean = false, onClick: Runnable) =
        menu.menuItem(label, shortcut, selected) { onClick.run() }

    @JvmStatic
    @JvmOverloads
    fun menuItem(menu: ContextMenuScope, label: String, shortcut: String = "", selected: Boolean = false, onClick: Runnable) =
        menu.menuItem(label, shortcut, selected) { onClick.run() }

    @JvmStatic
    @JvmOverloads
    fun menuItem(window: WindowScope, label: String, shortcut: String = "", selected: Boolean = false, onClick: Runnable) =
        window.menuItem(label, shortcut, selected) { onClick.run() }

    /** A menu opened by clicking [mouseButton] on the item added just before this; true while open. */
    @JvmStatic
    @JvmOverloads
    fun contextMenuItem(window: WindowScope, id: String? = null, mouseButton: Int = 1, content: Consumer<ContextMenuScope>): Boolean =
        window.contextMenuItem(id, mouseButton) { content.accept(this) }

    /** A menu opened by clicking [mouseButton] anywhere in the window; true while open. */
    @JvmStatic
    @JvmOverloads
    fun contextMenuWindow(
        window: WindowScope,
        id: String? = null,
        mouseButton: Int = 1,
        alsoOverItems: Boolean = true,
        content: Consumer<ContextMenuScope>,
    ): Boolean = window.contextMenuWindow(id, mouseButton, alsoOverItems) { content.accept(this) }

    /** A menu opened by clicking [mouseButton] where no window is; true while open. */
    @JvmStatic
    @JvmOverloads
    fun contextMenuVoid(window: WindowScope, id: String? = null, mouseButton: Int = 1, content: Consumer<ContextMenuScope>): Boolean =
        window.contextMenuVoid(id, mouseButton) { content.accept(this) }

    /** Runs [action] each frame the context menu is actually open. */
    @JvmStatic
    fun whileShown(menu: ContextMenuScope, action: Runnable) = menu.whileShown { action.run() }

    // ---- Tooltips ----

    /** A tooltip at the mouse this frame, whatever is hovered. */
    @JvmStatic
    fun tooltip(window: WindowScope, text: String) = window.tooltip(text)

    @JvmStatic
    fun setTooltip(window: WindowScope, text: String) = window.setTooltip(text)

    // ---- More controls ----

    /** An RGB colour editor bound to [r], [g] and [b] (0..1), with COLOR_ [flags] ORed together. */
    @JvmStatic
    @JvmOverloads
    fun colorEdit3(scope: LayoutScope, label: String, r: ImGuiState<Float>, g: ImGuiState<Float>, b: ImGuiState<Float>, flags: Int = 0) =
        scope.colorEdit3(label, r, g, b, flags)

    @JvmStatic
    @JvmOverloads
    fun colorEdit4(
        scope: LayoutScope,
        label: String,
        r: ImGuiState<Float>,
        g: ImGuiState<Float>,
        b: ImGuiState<Float>,
        a: ImGuiState<Float>,
        flags: Int = 0,
    ) = scope.colorEdit4(label, r, g, b, a, flags)

    /** A full colour picker bound to [r], [g], [b] and [a] (0..1). */
    @JvmStatic
    @JvmOverloads
    fun colorPicker4(
        scope: LayoutScope,
        label: String,
        r: ImGuiState<Float>,
        g: ImGuiState<Float>,
        b: ImGuiState<Float>,
        a: ImGuiState<Float>,
        flags: Int = 0,
    ) = scope.colorPicker4(label, r, g, b, a, flags)

    /** A number the user drags; [min] equal to [max] leaves it unbounded. SLIDER_ [flags] ORed together. */
    @JvmStatic
    @JvmOverloads
    fun dragFloat(
        scope: LayoutScope,
        label: String,
        state: ImGuiState<Float>,
        speed: Float = 1f,
        min: Float = 0f,
        max: Float = 0f,
        format: String = "%.3f",
        flags: Int = 0,
    ) = scope.dragFloat(label, state, speed, min, max, format, flags)

    @JvmStatic
    @JvmOverloads
    fun dragInt(
        scope: LayoutScope,
        label: String,
        state: ImGuiState<Int>,
        speed: Float = 1f,
        min: Int = 0,
        max: Int = 0,
        format: String = "%d",
        flags: Int = 0,
    ) = scope.dragInt(label, state, speed, min, max, format, flags)

    /** A slider with two handles, bound to [low] and [high], between [rangeMin] and [rangeMax]. */
    @JvmStatic
    @JvmOverloads
    fun rangeSliderFloat(
        window: WindowScope,
        label: String,
        low: ImGuiState<Float>,
        high: ImGuiState<Float>,
        rangeMin: Float,
        rangeMax: Float,
        format: String = "%.3f",
        flags: Int = 0,
    ): Boolean = window.rangeSliderFloat(label, low, high, rangeMin, rangeMax, format, flags)

    @JvmStatic
    @JvmOverloads
    fun rangeSliderInt(
        window: WindowScope,
        label: String,
        low: ImGuiState<Int>,
        high: ImGuiState<Int>,
        rangeMin: Int,
        rangeMax: Int,
        format: String = "%d",
        flags: Int = 0,
    ): Boolean = window.rangeSliderInt(label, low, high, rangeMin, rangeMax, format, flags)

    /** A button drawn as an arrow pointing [dir] (DIR_), showing [fallbackLabel] if the arrow cannot be drawn. */
    @JvmStatic
    fun arrowButton(scope: LayoutScope, id: String, dir: Int, fallbackLabel: String, onClick: Runnable) =
        scope.arrowButton(id, dir, fallbackLabel) { onClick.run() }

    /** A framed button showing [texture] at [iconSize], one standard frame tall. */
    @JvmStatic
    @JvmOverloads
    fun iconButton(
        scope: LayoutScope,
        id: String,
        texture: ImGuiTexture,
        iconSize: Float,
        buttonWidth: Float = iconSize + 8f,
        tintColor: Int = ImGuiColors.WHITE,
        onClick: Runnable,
    ) = scope.iconButton(id, texture, iconSize, buttonWidth, tintColor) { onClick.run() }

    /** A button showing the part of [texture] between ([uv0X], [uv0Y]) and ([uv1X], [uv1Y]). */
    @JvmStatic
    @JvmOverloads
    fun imageButton(
        scope: LayoutScope,
        texture: ImGuiTexture,
        width: Float,
        height: Float,
        uv0X: Float = 0f,
        uv0Y: Float = 0f,
        uv1X: Float = 1f,
        uv1Y: Float = 1f,
        framePadding: Int = -1,
        bgColor: Int = 0,
        tintColor: Int = -1,
        onClick: Runnable,
    ) = scope.imageButton(texture, width, height, uv0X, uv0Y, uv1X, uv1Y, framePadding, bgColor, tintColor) { onClick.run() }

    /** Checkboxes on a grid of [columns] columns, one per label and state of [items], in order. */
    @JvmStatic
    @JvmOverloads
    fun checkboxGrid(scope: LayoutScope, id: String, items: List<Map.Entry<String, ImGuiState<Boolean>>>, columns: Int = 2) =
        scope.checkboxGrid(id, items.map { it.key to it.value }, columns)

    /** A list box of [items] where the user can pick several; the picked indices live in [selected]. */
    @JvmStatic
    @JvmOverloads
    fun multiSelectListBox(
        window: WindowScope,
        label: String,
        items: List<String>,
        selected: ImGuiState<Set<Int>>,
        width: Float = 0f,
        height: Float = 0f,
    ): Boolean = window.multiSelectListBox(label, items, selected, width, height)

    // ---- More layout ----

    /** Indents what follows by [width], or the style's indent when 0. */
    @JvmStatic
    @JvmOverloads
    fun indent(scope: LayoutScope, width: Float = 0f) = scope.indent(width)

    @JvmStatic
    @JvmOverloads
    fun unindent(scope: LayoutScope, width: Float = 0f) = scope.unindent(width)

    /** Splits what follows into [count] legacy columns; move between them with [nextColumn]. */
    @JvmStatic
    @JvmOverloads
    fun columns(scope: LayoutScope, count: Int = 1, id: String? = null, border: Boolean = true) = scope.columns(count, id, border)

    @JvmStatic
    fun nextColumn(scope: LayoutScope) = scope.nextColumn()

    /** The next cell of a [table] row. */
    @JvmStatic
    fun nextColumn(table: TableScope) = table.nextColumn()

    @JvmStatic
    fun setColumnWidth(scope: LayoutScope, columnIndex: Int, width: Float) = scope.setColumnWidth(columnIndex, width)

    /** Makes controls [width] pixels wide until [popItemWidth]. */
    @JvmStatic
    fun pushItemWidth(scope: LayoutScope, width: Float) = scope.pushItemWidth(width)

    @JvmStatic
    fun popItemWidth(scope: LayoutScope) = scope.popItemWidth()

    /** Makes only the next control [width] pixels wide; negative leaves that much room on the right. */
    @JvmStatic
    fun setNextItemWidth(scope: LayoutScope, width: Float) = scope.setNextItemWidth(width)

    /** [content] drawn with the single-value style variable [styleVar] set to [value]. */
    @JvmStatic
    fun styleVar(scope: LayoutScope, styleVar: ImGuiStyleVar, value: Float, content: Consumer<ChildScope>) =
        scope.styleVar(styleVar, value) { content.accept(this) }

    /** [content] drawn with the two-value style variable [styleVar] set to ([x], [y]). */
    @JvmStatic
    fun styleVar(scope: LayoutScope, styleVar: ImGuiStyleVar, x: Float, y: Float, content: Consumer<ChildScope>) =
        scope.styleVar(styleVar, x, y) { content.accept(this) }

    /** Lowers the next text to line up with the controls beside it. */
    @JvmStatic
    fun alignTextToFramePadding(scope: LayoutScope) = scope.alignTextToFramePadding()

    /** Whether the mouse was over the item added just before this, as of the previous frame. */
    @JvmStatic
    fun isItemHovered(scope: LayoutScope): Boolean = scope.isItemHovered()

    /** Whether the item added just before this was clicked with [mouseButton] (MOUSE_), as of the previous frame. */
    @JvmStatic
    @JvmOverloads
    fun isItemClicked(scope: LayoutScope, mouseButton: Int = 0): Boolean = scope.isItemClicked(mouseButton)
}

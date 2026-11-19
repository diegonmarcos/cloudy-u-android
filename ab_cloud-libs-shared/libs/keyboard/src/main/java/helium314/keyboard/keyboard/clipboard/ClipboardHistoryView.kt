// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.uikit.KitImeComposeView
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.KeyboardViewState
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.keyboard.internal.KeyDrawParams
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.panel.PanelLayoutParams
import helium314.keyboard.keyboard.panel.drawableBackground
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.createToolbarKey
import helium314.keyboard.latin.utils.getCodeForToolbarKey
import helium314.keyboard.latin.utils.getCodeForToolbarKeyLongClick
import helium314.keyboard.latin.utils.getEnabledClipboardToolbarKeys
import helium314.keyboard.latin.ClipboardHtml
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.stateColor
import helium314.keyboard.latin.utils.setToolbarButtonsActivatedStateOnPrefChange
import helium314.keyboard.latin.utils.toKitPalette

/**
 * The clipboard panel, in Compose under CloudKitTheme (the keyboard theme's palette roles): pin-list
 * tabs, the search box, the clip cards in a staggered grid (tap pastes, long-press pins or unpins,
 * swipe left removes an unpinned clip) and the bottom-row key surface, which stays a
 * [MainKeyboardView] hosted as an AndroidView because key touch handling is latency-critical.
 *
 * KeyboardSwitcher still inflates it from main_keyboard_frame and drives it through
 * [startClipboardHistory] / [stopClipboardHistory]; the strip keys above the panel are not part of
 * this view (they live in the suggestion strip container).
 */
@SuppressLint("CustomViewStyleable")
class ClipboardHistoryView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
) : KitImeComposeView(context, attrs), View.OnClickListener,
    ClipboardHistoryManager.HistoryChangeListener, OnKeyEventListener,
    View.OnLongClickListener, SharedPreferences.OnSharedPreferenceChangeListener {

    /** Everything the panel draws with that is fixed for one opening (rebuilt by every [startClipboardHistory]). */
    private class Look(
        val typeface: Typeface?,
        val textColor: Int,
        val textSizePx: Float,
        val labelSizePx: Float,
        val colCount: Int,
        val layout: PanelLayoutParams,
        val keyboardWidth: Int,
        val keyboardHeight: Int,
        val padLeft: Int,
        val padRight: Int,
    )

    private class BottomRow(val editorInfo: EditorInfo, val listener: KeyboardActionListener)

    private val pinIconId: Int
    private val keyBackgroundId: Int

    private var manager by mutableStateOf<ClipboardHistoryManager?>(null)
    private var look by mutableStateOf<Look?>(null)
    private var bottomRow by mutableStateOf<BottomRow?>(null)
    private var bottomRowView: MainKeyboardView? = null
    private var bottomRowConfigured: BottomRow? = null
    /** Bumped on every history, tab or search change: the list is read again from the manager. */
    private var version by mutableIntStateOf(0)
    /** Bumped on every opening: theme colours are read again, the search box is cleared. */
    private var opening by mutableIntStateOf(0)
    private var pinPickerFor by mutableStateOf<Long?>(null)
    private val searchState = TextFieldState()
    private val toolbarKeys = mutableListOf<ImageButton>()

    lateinit var keyboardActionListener: KeyboardActionListener

    init {
        val clipboardViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.ClipboardHistoryView, R.attr.clipboardHistoryViewStyle, R.style.ClipboardHistoryView)
        pinIconId = clipboardViewAttr.getResourceId(R.styleable.ClipboardHistoryView_iconPinnedClip, 0)
        clipboardViewAttr.recycle()
        @SuppressLint("UseKtx") // suggestion does not work
        val keyboardViewAttr = context.obtainStyledAttributes(attrs, R.styleable.KeyboardView,
            R.attr.clipboardHistoryViewStyle, R.style.KeyboardView)
        keyBackgroundId = keyboardViewAttr.getResourceId(R.styleable.KeyboardView_keyBackground, 0)
        keyboardViewAttr.recycle()
        // #843: the strip keys are NOT built here any more. Built once from the settings of the
        // inflation instant, a view inflated while the keyguard was locked (secondary strip
        // hidden) had no CLOSE_HISTORY for the rest of its life. See rebuildStripKeys().
        fitsSystemWindows = true
    }

    override fun palette(): KitPalette {
        opening // read: a new opening re-reads the theme's colours
        return Settings.getValues().mColors.toKitPalette()
    }

    @Composable
    override fun Body() {
        val m = manager ?: return
        val look = look ?: return
        val density = LocalDensity.current
        val ctx = LocalContext.current
        val colors = Settings.getValues().mColors
        val textColor = Color(look.textColor)
        val fontFamily = look.typeface?.let { FontFamily(it) }
        val keyBackground = remember(opening) {
            ContextCompat.getDrawable(ctx, keyBackgroundId)?.mutate()?.also { colors.setColor(it, ColorType.KEY_BACKGROUND) }
        }
        fun dp(px: Int): Dp = with(density) { px.toDp() }

        LaunchedEffect(opening) {
            searchState.clearText()
            snapshotFlow { searchState.text.toString() }.collect {
                m.searchQuery = it
                version++
            }
        }

        Box(Modifier.size(dp(look.keyboardWidth), dp(look.keyboardHeight))) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f)) { Tabs(m, colors.get(ColorType.KEY_TEXT), fontFamily) }
                    KeepHtmlToggle(colors.get(ColorType.KEY_TEXT), fontFamily)
                }
                SearchBox(keyBackground, textColor, fontFamily)
                Box(Modifier.fillMaxWidth().height(dp(look.layout.listHeight))) {
                    val entries = remember(version, m, opening) {
                        List(m.getHistorySize()) { m.getHistoryEntry(it) }.filterNotNull()
                    }
                    if (entries.isEmpty()) {
                        Text("¯\\_(ツ)_/¯", color = textColor, fontFamily = fontFamily, textAlign = TextAlign.Center,
                            fontSize = with(density) { (look.labelSizePx * 2).toSp() },
                            modifier = Modifier.align(Alignment.Center))
                    } else {
                        val gap = look.layout
                        LazyVerticalStaggeredGrid(
                            columns = StaggeredGridCells.Fixed(look.colCount),
                            contentPadding = PaddingValues(start = dp(look.padLeft), end = dp(look.padRight)),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(entries, key = { it.id }) { entry ->
                                ClipCard(m, entry, look, keyBackground, textColor, fontFamily,
                                    Modifier.padding(start = dp(gap.keyHorizontalGap / 2), end = dp(gap.keyHorizontalGap / 2),
                                        top = dp(gap.keyHorizontalGap / 2), bottom = dp(gap.keyVerticalGap / 2)))
                            }
                        }
                    }
                    pinPickerFor?.let { PinPicker(m, it) }
                }
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { MainKeyboardView(it, null).also { view -> bottomRowView = view } },
                    update = { view -> bottomRow?.let { setupBottomRow(view, it) } },
                )
            }
        }
    }

    /**
     * #1813 "Keep HTML formatting" in the panel's top line, next to the tabs: same pref as the
     * Clipboard settings screen (ClipboardHtml.PREF_KEEP_HTML), the check shows whether it is on.
     */
    @Composable
    private fun KeepHtmlToggle(keyText: Int, fontFamily: FontFamily?) {
        val prefs = LocalContext.current.prefs()
        var on by remember(version) { mutableStateOf(ClipboardHtml.isKeepHtml(prefs::getBoolean)) }
        Text(
            text = (if (on) "\u2611 " else "\u2610 ") + stringResource(R.string.clipboard_keep_html_short),
            color = Color(keyText),
            fontFamily = fontFamily,
            fontSize = 14.sp,
            modifier = Modifier
                .alpha(if (on) 1f else 0.5f)
                .clickable {
                    on = ClipboardHtml.toggled(on)
                    prefs.edit().putBoolean(ClipboardHtml.PREF_KEEP_HTML, on).apply()
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }

    @Composable
    private fun Tabs(m: ClipboardHistoryManager, keyText: Int, fontFamily: FontFamily?) {
        version // read: tabs follow pin / rename / list changes
        val current = m.getCurrentList()
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            (listOf<String?>(null) + m.getListNames()).forEach { listName ->
                Text(
                    text = listName ?: stringResource(R.string.clipboard),
                    color = Color(keyText),
                    fontFamily = fontFamily,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .alpha(if (listName == current) 1f else 0.5f)
                        .clickable {
                            m.setCurrentList(listName)
                            version++
                        }
                        // Pin-list rename moved to Settings -> Clipboard (can't type into a dialog while
                        // the clipboard panel replaces the keyboard).
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }

    @Composable
    private fun SearchBox(keyBackground: Drawable?, textColor: Color, fontFamily: FontFamily?) {
        // Search box: themed to match the other clipboard views; cleared when re-opened.
        BasicTextField(
            state = searchState,
            lineLimits = TextFieldLineLimits.SingleLine,
            textStyle = TextStyle(color = textColor, fontFamily = fontFamily),
            cursorBrush = SolidColor(textColor),
            modifier = Modifier.fillMaxWidth(),
            decorator = { inner ->
                Box(Modifier.fillMaxWidth().drawableBackground(keyBackground, false)
                    .padding(horizontal = 8.dp, vertical = 4.dp)) {
                    if (searchState.text.isEmpty())
                        Text(stringResource(R.string.clipboard_search_hint), color = textColor.copy(alpha = 0.4f),
                            fontFamily = fontFamily)
                    inner()
                }
            },
        )
    }

    @Composable
    private fun ClipCard(
        m: ClipboardHistoryManager,
        entry: ClipboardHistoryEntry,
        look: Look,
        keyBackground: Drawable?,
        textColor: Color,
        fontFamily: FontFamily?,
        modifier: Modifier,
    ) {
        val ctx = LocalContext.current
        val density = LocalDensity.current
        val colors = Settings.getValues().mColors
        var pressed by remember { mutableStateOf(false) }
        val id = entry.id
        val dismissState = rememberSwipeToDismissBoxState(confirmValueChange = { target ->
            // pinned clips cannot be swiped away; positions are looked up now, the list may have moved
            val index = (0 until m.getHistorySize()).firstOrNull { m.getHistoryEntry(it)?.id == id }
            if (target == SwipeToDismissBoxValue.EndToStart && index != null && m.canRemove(index)) {
                m.removeEntry(index)
                version++
                true
            } else false
        })
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = !entry.isPinned,
            backgroundContent = {},
            modifier = modifier,
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .drawableBackground(keyBackground, pressed)
                    .pointerInput(id) {
                        // haptic and audio feedback come from the keyboard settings (onKeyDown), not the system's
                        detectTapGestures(
                            onPress = {
                                pressed = true
                                onKeyDown(id)
                                tryAwaitRelease()
                                pressed = false
                            },
                            onTap = { onKeyUp(id) },
                            onLongPress = {
                                if (m.getCurrentList() != null) m.unpin(id) // already viewing this clip's pin list -> unpin directly
                                else pinPickerFor = id
                            },
                        )
                    },
            ) {
                if (entry.isPinned && pinIconId != 0) {
                    Image(
                        painter = painterResource(pinIconId),
                        contentDescription = stringResource(R.string.clipboard_history_pinned_first),
                        colorFilter = ColorFilter.tint(Color(colors.get(ColorType.CLIPBOARD_PIN))),
                        modifier = Modifier
                            .padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
                            .size(with(density) { ctx.resources.getDimensionPixelSize(R.dimen.config_clipboard_pinned_icon_size).toDp() }),
                    )
                }
                Column(Modifier.weight(1f).padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 6.dp)) {
                    val preview = remember(id) { if (entry.filename != null) entry.loadPreview(ctx) else null }
                    preview?.image?.let {
                        Image(it.asImageBitmap(), null, modifier = Modifier.align(Alignment.CenterHorizontally),
                            contentScale = ContentScale.Fit)
                    }
                    preview?.icon?.let {
                        Image(it.asImageBitmap(), null, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                    // truncate displayed text for performance reasons
                    val text = if (entry.filename != null) preview?.text else entry.text?.take(1000)
                    if (!text.isNullOrEmpty())
                        Text(text, color = textColor, fontFamily = fontFamily, maxLines = 4, overflow = TextOverflow.Ellipsis,
                            fontSize = with(density) { look.textSizePx.toSp() })
                    if (!entry.html.isNullOrBlank()) // #1813 marker: this clip carries HTML
                        Text(stringResource(R.string.clipboard_html_marker), color = textColor.copy(alpha = 0.6f), fontFamily = fontFamily,
                            fontSize = with(density) { (look.textSizePx * 0.7f).toSp() })
                }
            }
        }
    }

    /** Long-press on an unpinned clip: which list to copy it into (existing, or a freshly-numbered new one). */
    @Composable
    private fun PinPicker(m: ClipboardHistoryManager, id: Long) {
        val p = LocalKitPalette.current
        Box(Modifier.fillMaxSize().background(p.surface.copy(alpha = 0.6f)).clickable { pinPickerFor = null },
            contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth(0.8f).background(p.surfaceSelected, RoundedCornerShape(8.dp))
                .clickable(enabled = false) {}.padding(vertical = 8.dp)) {
                Text(stringResource(R.string.clipboard_pin_choose_list), color = p.textPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                (m.getListNames() + null).forEach { name ->
                    Text(name ?: stringResource(R.string.clipboard_pin_new_list), color = p.textPrimary,
                        modifier = Modifier.fillMaxWidth().clickable {
                            pinPickerFor = null
                            if (name != null) m.pinToList(id, name) else m.pinToNewList(id)
                        }.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
        }
    }

    private fun setupBottomRow(view: MainKeyboardView, request: BottomRow) {
        if (bottomRowConfigured === request) return
        bottomRowConfigured = request
        view.setKeyboardActionListener(request.listener)
        PointerTracker.switchTo(view)
        val kls = KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(context, request.editorInfo)
        view.setKeyboard(kls.getKeyboard(KeyboardId.ELEMENT_CLIPBOARD_BOTTOM_ROW))
    }

    /**
     * #843: the clipboard strip's keys, rebuilt at EVERY open from the current settings by
     * KeyboardViewState.clipboardStripKeys, which always includes CLOSE_HISTORY (alone when the
     * secondary strip is hidden). The panel can therefore never be shown without its close key.
     */
    private fun rebuildStripKeys() {
        val colors = Settings.getValues().mColors
        val clipboardStrip = KeyboardSwitcher.getInstance().clipboardStrip
        // #1077: the strip's own background is the shared toolbar token (ColorType.STRIP_BACKGROUND, what the
        // typing strip and the emoji tabs paint). Only the keys had it, so the empty part of the line showed the
        // window's white.
        val stripBackground = colors.stateColor(ColorType.STRIP_BACKGROUND)
        clipboardStrip.setBackgroundColor(stripBackground)
        (clipboardStrip.parent as? View)?.setBackgroundColor(stripBackground)
        toolbarKeys.forEach { clipboardStrip.removeView(it) }
        toolbarKeys.clear()
        val keys = KeyboardViewState.clipboardStripKeys(
            getEnabledClipboardToolbarKeys(context.prefs()), Settings.getValues().mSecondaryStripVisible, ToolbarKey.CLOSE_HISTORY)
        for (key in keys) {
            val button = try { createToolbarKey(context, key) } catch (t: Throwable) {
                Log.e("ClipboardHistoryView", "clipboard strip key $key dropped, it cannot be built", t); continue
            }
            clipboardStrip.addView(button)
            button.setOnClickListener(this@ClipboardHistoryView)
            button.setOnLongClickListener(this@ClipboardHistoryView)
            colors.setColor(button, ColorType.TOOL_BAR_KEY)
            colors.setBackground(button, ColorType.STRIP_BACKGROUND)
            toolbarKeys.add(button)
        }
    }

    private fun setupToolbarKeys() {
        // set layout params
        val toolbarKeyLayoutParams = LinearLayout.LayoutParams(
            resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_edge_key_width),
            LinearLayout.LayoutParams.MATCH_PARENT)
        toolbarKeys.forEach { it.layoutParams = toolbarKeyLayoutParams }
    }

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (!enabled) return
        // TODO: Should use LAYER_TYPE_SOFTWARE when hardware acceleration is off?
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun startClipboardHistory(
            historyManager: ClipboardHistoryManager,
            keyVisualAttr: KeyVisualAttributes?,
            editorInfo: EditorInfo,
            keyboardActionListener: KeyboardActionListener
    ) {
        historyManager.setCurrentList(null) // always reopen on the default (unpinned) page
        historyManager.searchQuery = "" // clear any leftover search from the previous session
        rebuildStripKeys()
        setupToolbarKeys()
        historyManager.prepareClipboardHistory()
        historyManager.setHistoryChangeListener(this)
        pinPickerFor = null

        val settings = Settings.getInstance()
        val layout = PanelLayoutParams(context)
        val params = KeyDrawParams()
        params.updateParams(layout.bottomRowKeyboardHeight, keyVisualAttr)
        KeyboardTypeface.customTypeface()?.let { params.mTypeface = it }
        val keyboardWidth = ResourceUtils.getKeyboardWidth(context, settings.current)
        // SuperApp: clipboard card scale. The orientation/size-qualified integer resource gives the
        // base column count; the user's card-scale pref (<1 = smaller cards) auto-multiplies it so
        // more clips fit per line (e.g. base 2 at scale 0.5 -> 4 columns). Min 1 column.
        val baseColCount = resources.getInteger(R.integer.config_clipboard_keyboard_col_count)
        val clipScale = Settings.getValues().mClipboardFontScale
        // set side padding
        val keyboardAttr = context.obtainStyledAttributes(
            null, R.styleable.Keyboard, R.attr.keyboardStyle, R.style.Keyboard)
        val leftPadding = (keyboardAttr.getFraction(R.styleable.Keyboard_keyboardLeftPadding,
            keyboardWidth, keyboardWidth, 0f) * settings.current.mSidePaddingScale).toInt()
        val rightPadding = (keyboardAttr.getFraction(R.styleable.Keyboard_keyboardRightPadding,
            keyboardWidth, keyboardWidth, 0f) * settings.current.mSidePaddingScale).toInt()
        keyboardAttr.recycle()
        look = Look(
            typeface = params.mTypeface,
            textColor = params.mTextColor,
            // SuperApp: scale clipboard card text by the card-scale pref (paired with the auto column-count change)
            textSizePx = params.mLabelSize.toFloat() * clipScale,
            labelSizePx = params.mLabelSize.toFloat(),
            colCount = Math.round(baseColCount / clipScale).coerceAtLeast(1),
            layout = layout,
            keyboardWidth = keyboardWidth,
            keyboardHeight = ResourceUtils.getSecondaryKeyboardHeight(resources, Settings.getValues()),
            padLeft = leftPadding,
            padRight = rightPadding,
        )
        manager = historyManager
        searchState.clearText() // clear search box on re-open
        opening++
        version++
        val request = BottomRow(editorInfo, keyboardActionListener)
        bottomRow = request
        bottomRowView?.let { setupBottomRow(it, request) }
    }

    fun stopClipboardHistory() {
        manager?.setHistoryChangeListener(null)
        pinPickerFor = null
    }

    override fun onClick(view: View) {
        val tag = view.tag
        if (tag is ToolbarKey) {
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
            val code = getCodeForToolbarKey(tag)
            if (code != KeyCode.UNSPECIFIED) {
                keyboardActionListener.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
                return
            }
        }
    }

    override fun onLongClick(view: View): Boolean {
        val tag = view.tag
        if (tag is ToolbarKey) {
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_LONG_PRESS)
            val longClickCode = getCodeForToolbarKeyLongClick(tag)
            if (longClickCode != KeyCode.UNSPECIFIED) {
                keyboardActionListener.onCodeInput(
                    longClickCode,
                    Constants.NOT_A_COORDINATE,
                    Constants.NOT_A_COORDINATE,
                    false
                )
            }
            return true
        }
        return false
    }

    override fun onKeyDown(clipId: Long) {
        keyboardActionListener.onPressKey(KeyCode.NOT_SPECIFIED, 0, true, HapticEvent.KEY_PRESS)
    }

    override fun onKeyUp(clipId: Long) {
        val clipContent = manager?.getHistoryEntryContent(clipId)
        if (clipContent?.filename != null) keyboardActionListener.onContent(clipContent.getContentInfo(context))
        else if (clipContent?.html != null && manager?.pasteEntryAsHtml(clipContent) == true) Unit // #1813 pasted as HTML
        else keyboardActionListener.onTextInput(clipContent?.text)
        keyboardActionListener.onReleaseKey(KeyCode.NOT_SPECIFIED, false)
        if (Settings.getValues().mAlphaAfterClipHistoryEntry)
            keyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
    }

    // coarse refresh: a pin/unpin or new-clip event can change which page an entry belongs to,
    // so the (small) list is read again rather than tracking fine-grained positions
    override fun onHistoryChanged() {
        version++
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        setToolbarButtonsActivatedStateOnPrefChange(KeyboardSwitcher.getInstance().clipboardStrip, key)

        // The setting can only be changed from a settings screen, but adding it to this listener seems necessary: https://github.com/HeliBorg/HeliBoard/pull/1903#issuecomment-3478424606
        val m = manager
        if (m != null && key == Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST) {
            // Ensure settings are reloaded first
            Settings.getInstance().onSharedPreferenceChanged(prefs, key)
            m.sortHistoryEntries()
            version++
        }
    }
}

/*
 * Copyright (C) 2008 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import helium314.keyboard.event.Event;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.keyboard.KeyboardLayoutSet.KeyboardLayoutSetException;
import helium314.keyboard.keyboard.autocomplete.AutocompleteView;
import helium314.keyboard.keyboard.clipboard.ClipboardHistoryView;
import helium314.keyboard.keyboard.handwrite.HandwriteView;
import helium314.keyboard.keyboard.emoji.EmojiPalettesView;
import helium314.keyboard.keyboard.internal.KeyboardState;
import helium314.keyboard.keyboard.internal.keyboard_parser.EmojiParserKt;
import helium314.keyboard.latin.utils.FloatingKeyboardUtils;
import helium314.keyboard.latin.FakeToastView;
import helium314.keyboard.latin.InputView;
import helium314.keyboard.latin.KeyboardWrapperView;
import helium314.keyboard.latin.LatinIME;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.RichInputMethodManager;
import helium314.keyboard.latin.RichInputMethodSubtype;
import helium314.keyboard.latin.WordComposer;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsKt;
import helium314.keyboard.latin.settings.SettingsValues;
import helium314.keyboard.latin.suggestions.SuggestionStripView;
import helium314.keyboard.latin.utils.CapsModeUtils;
import helium314.keyboard.latin.utils.FoldableUtils;
import helium314.keyboard.latin.utils.KtxKt;
import helium314.keyboard.latin.utils.LanguageOnSpacebarUtils;
import helium314.keyboard.latin.utils.Log;
import helium314.keyboard.latin.utils.RecapitalizeMode;
import helium314.keyboard.latin.utils.ResourceUtils;
import helium314.keyboard.latin.utils.ScriptUtils;
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional;
import helium314.keyboard.latin.utils.ToolbarMode;

public final class KeyboardSwitcher implements KeyboardState.SwitchActions {
    private static final String TAG = KeyboardSwitcher.class.getSimpleName();

    private InputView mCurrentInputView;
    private KeyboardWrapperView mKeyboardViewWrapper;
    private View mMainKeyboardFrame;
    private MainKeyboardView mKeyboardView;
    private EmojiPalettesView mEmojiPalettesView;
    private View mEmojiTabStripView;
    private LinearLayout mClipboardStripView;
    private HorizontalScrollView mClipboardStripScrollView;
    private SuggestionStripView mSuggestionStripView;
    private FrameLayout mStripContainer;
    private ClipboardHistoryView mClipboardHistoryView;
    private HandwriteView mHandwriteView; // #1781: HandWrite is its own panel, drawn by the view itself
    private AutocompleteView mAutocompleteView; // #1103: Autocomplete is its own panel, not a page of the clipboard
    private FakeToastView mFakeToastView;
    private ImageView mBackgroundGatheringIndicator;
    private LatinIME mLatinIME;
    private RichInputMethodManager mRichImm;
    private boolean mIsHardwareAcceleratedDrawingEnabled;

    private KeyboardState mState;
    /** #843: the one model of what the input view shows; see {@link #applyViewState()}. */
    private final KeyboardViewState mViewState = new KeyboardViewState();

    private KeyboardLayoutSet mKeyboardLayoutSet;

    private KeyboardTheme mKeyboardTheme;
    private Context mThemeContext;
    private int mCurrentUiMode;
    private int mCurrentOrientation;
    private int mCurrentDpi;
    private boolean mThemeNeedsReload;

    @SuppressLint("StaticFieldLeak") // this is a keyboard, we want to keep it alive in background
    private static final KeyboardSwitcher sInstance = new KeyboardSwitcher();

    public static KeyboardSwitcher getInstance() {
        return sInstance;
    }

    private KeyboardSwitcher() {
        // Intentional empty constructor for singleton.
    }

    public static void init(final LatinIME latinIme) {
        sInstance.initInternal(latinIme);
    }

    private void initInternal(final LatinIME latinIme) {
        mLatinIME = latinIme;
        mRichImm = RichInputMethodManager.getInstance();
        mState = new KeyboardState(this);
        mIsHardwareAcceleratedDrawingEnabled = mLatinIME.enableHardwareAcceleration();
    }

    public void updateKeyboardTheme(@NonNull Context displayContext) {
        final boolean themeUpdated = updateKeyboardThemeAndContextThemeWrapper(
                displayContext, KeyboardTheme.getKeyboardTheme(displayContext));
        if (themeUpdated) {
            Settings settings = Settings.getInstance();
            settings.loadSettings(displayContext, settings.getCurrent().mLocale, settings.getCurrent().mInputAttributes);
            // #776: this is the one path that recreates the input view (and the toolbar row
            // with it) without a new service; logged so a row built here can be traced.
            Log.i(TAG, "keyboard theme updated, recreating the input view (locked="
                    + settings.getCurrent().mIsLocked + ")");
            if (mKeyboardView != null)
                mLatinIME.setInputView(onCreateInputView(displayContext, mIsHardwareAcceleratedDrawingEnabled));
        } else if (mCurrentInputView != null && mLatinIME.hasSuggestionStripView()
                    == (Settings.getValues().mToolbarMode == ToolbarMode.HIDDEN || mLatinIME.isEmojiSearch())) {
            mLatinIME.updateSuggestionStripView(mCurrentInputView);
        }
    }

    private boolean updateKeyboardThemeAndContextThemeWrapper(final Context context, final KeyboardTheme keyboardTheme) {
        final Resources res = context.getResources();
        if (mThemeNeedsReload
                || mThemeContext == null
                || !keyboardTheme.equals(mKeyboardTheme)
                || mCurrentDpi != res.getDisplayMetrics().densityDpi
                || mCurrentOrientation != res.getConfiguration().orientation
                || (mCurrentUiMode & Configuration.UI_MODE_NIGHT_MASK) != (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                || !mThemeContext.getResources().equals(res)
                || Settings.getValues().mColors.haveColorsChanged(context)) {
            mThemeNeedsReload = false;
            mKeyboardTheme = keyboardTheme;
            mThemeContext = new ContextThemeWrapper(context, keyboardTheme.mStyleId);
            mCurrentUiMode = res.getConfiguration().uiMode;
            mCurrentOrientation = res.getConfiguration().orientation;
            mCurrentDpi = res.getDisplayMetrics().densityDpi;
            KeyboardLayoutSet.onKeyboardThemeChanged();
            return true;
        }
        return false;
    }

    public void loadKeyboard(final EditorInfo editorInfo, final SettingsValues settingsValues,
            final int currentAutoCapsState, @Nullable final RecapitalizeMode currentRecapitalizeState,
            KeyboardLayoutSet.InternalAction internalAction) {
        final KeyboardLayoutSet.Builder builder = new KeyboardLayoutSet.Builder(
                mThemeContext, editorInfo);
        final int keyboardWidth = ResourceUtils.getKeyboardWidth(mThemeContext, settingsValues);
        final int keyboardHeight = ResourceUtils.getKeyboardHeight(mThemeContext.getResources(), settingsValues);
        mKeyboardLayoutSet = builder.setKeyboardGeometry(keyboardWidth, keyboardHeight)
                .setSubtype(mRichImm.getCurrentSubtype())
                .setVoiceInputKeyEnabled(settingsValues.mShowsVoiceInputKey)
                .setNumberRowEnabled(settingsValues.mShowsNumberRow)
                .setNumberRowInSymbolsEnabled(settingsValues.mShowsNumberRowInSymbols)
                .setLanguageSwitchKeyEnabled(settingsValues.isLanguageSwitchKeyEnabled())
                .setEmojiKeyEnabled(settingsValues.mShowsEmojiKey)
                .setSplitLayoutEnabled(settingsValues.mIsSplitKeyboardEnabled)
                .setOneHandedModeEnabled(settingsValues.mOneHandedModeEnabled)
                .setInternalAction(internalAction)
                .build();
        try {
            mState.onLoadKeyboard(currentAutoCapsState, currentRecapitalizeState, settingsValues.mOneHandedModeEnabled);
        } catch (KeyboardLayoutSetException e) {
            Log.e(TAG, "loading keyboard failed: " + e.mKeyboardId, e.getCause());
            try {
                final InputMethodSubtype defaults = SubtypeUtilsAdditional.INSTANCE.createDefaultSubtype(mRichImm.getCurrentSubtypeLocale());
                mKeyboardLayoutSet = builder.setKeyboardGeometry(keyboardWidth, keyboardHeight)
                        .setSubtype(RichInputMethodSubtype.Companion.get(defaults))
                        .setNumberRowEnabled(settingsValues.mShowsNumberRow)
                        .setNumberRowInSymbolsEnabled(settingsValues.mShowsNumberRowInSymbols)
                        .setLanguageSwitchKeyEnabled(settingsValues.isLanguageSwitchKeyEnabled())
                        .setEmojiKeyEnabled(settingsValues.mShowsEmojiKey)
                        .build();
                mState.onLoadKeyboard(currentAutoCapsState, currentRecapitalizeState, false);
                showToast("error loading the keyboard, falling back to defaults", false);
            } catch (KeyboardLayoutSetException e2) {
                Log.e(TAG, "even fallback to defaults failed: " + e2.mKeyboardId, e2.getCause());
            }
        }
    }

    public void saveKeyboardState() {
        if (getKeyboard() != null || isShowingEmojiPalettes() || isShowingClipboardHistory() || isShowingAutocomplete() || isShowingHandwrite()) {
            mState.onSaveKeyboardState();
        }
    }

    public void onHideWindow() {
        // #843: a hidden keyboard never comes back inside a panel whose strip was built for
        // settings that no longer hold; the next show starts from typing.
        closePanelIfOpen("window hidden");
        if (mKeyboardView != null) {
            mKeyboardView.onHideWindow();
        }
    }

    private void setKeyboard(final int keyboardId, @NonNull final KeyboardSwitchState toggleState) {
        // with a hardware keyboard we might get here without ever calling onCreateInputView, so don't crash
        if (mKeyboardView == null) return;

        // Make {@link MainKeyboardView} visible and hide {@link EmojiPalettesView}.
        final SettingsValues currentSettingsValues = Settings.getValues();
        setMainKeyboardFrame(currentSettingsValues, toggleState);
        // TODO: pass this object to setKeyboard instead of getting the current values.
        final MainKeyboardView keyboardView = mKeyboardView;
        final Keyboard oldKeyboard = keyboardView.getKeyboard();
        final Keyboard newKeyboard = mKeyboardLayoutSet.getKeyboard(keyboardId);
        keyboardView.setKeyboard(newKeyboard);
        mCurrentInputView.setKeyboardTopPadding(newKeyboard.mTopPadding);
        keyboardView.setKeyPreviewPopupEnabled(currentSettingsValues.mKeyPreviewPopupOn);
        keyboardView.updateShortcutKey(mRichImm.isShortcutImeReady());
        final boolean subtypeChanged = (oldKeyboard == null) || !newKeyboard.mId.mSubtype.equals(oldKeyboard.mId.mSubtype);
        final int languageOnSpacebarFormatType = LanguageOnSpacebarUtils.getLanguageOnSpacebarFormatType(newKeyboard.mId.mSubtype);
        final boolean hasMultipleEnabledIMEsOrSubtypes = mRichImm.hasMultipleEnabledIMEsOrSubtypes(true);
        keyboardView.startDisplayLanguageOnSpacebar(subtypeChanged, languageOnSpacebarFormatType, hasMultipleEnabledIMEsOrSubtypes);

        if (currentSettingsValues.needsToLookupSuggestions()
                                    && (currentSettingsValues.mInlineEmojiSearch || currentSettingsValues.mSuggestEmojis)) {
            EmojiParserKt.loadEmojiDefaultVersionsAndPopupSpecs(mThemeContext);
        }
    }

    public Keyboard getKeyboard() {
        if (mKeyboardView != null) {
            return mKeyboardView.getKeyboard();
        }
        return null;
    }

    // TODO: Remove this method. Come up with a more comprehensive way to reset the keyboard layout
    // when a keyboard layout set doesn't get reloaded in LatinIME.onStartInputViewInternal().
    public void resetKeyboardStateToAlphabet(final int currentAutoCapsState,
            @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onResetKeyboardStateToAlphabet(currentAutoCapsState, currentRecapitalizeState);
    }

    public void onPressKey(final int code, final boolean isSinglePointer,
            final int currentAutoCapsState, @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onPressKey(code, isSinglePointer, currentAutoCapsState, currentRecapitalizeState);
    }

    public void onReleaseKey(final int code, final boolean withSliding,
            final int currentAutoCapsState, @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onReleaseKey(code, withSliding, currentAutoCapsState, currentRecapitalizeState);
        if (withSliding && code == KeyCode.NUMPAD) rememberPanelAfterManualSwitch();
    }

    public void onFinishSlidingInput(final int currentAutoCapsState,
            @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onFinishSlidingInput(currentAutoCapsState, currentRecapitalizeState);
        rememberPanelAfterManualSwitch();
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setAlphabetKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setAlphabetManualShiftedKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetManualShiftedKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET_MANUAL_SHIFTED, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setAlphabetAutomaticShiftedKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetAutomaticShiftedKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET_AUTOMATIC_SHIFTED, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setAlphabetShiftLockedKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetShiftLockedKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCKED, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setAlphabetShiftLockShiftedKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetShiftLockShiftedKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCK_SHIFTED, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setSymbolsKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setSymbolsKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_SYMBOLS, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setSymbolsShiftedKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setSymbolsShiftedKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_SYMBOLS_SHIFTED, KeyboardSwitchState.SYMBOLS_SHIFTED);
    }

    public boolean isImeSuppressedByHardwareKeyboard(
            @NonNull final SettingsValues settingsValues,
            @NonNull final KeyboardSwitchState toggleState) {
        return settingsValues.mHasHardwareKeyboard && toggleState == KeyboardSwitchState.HIDDEN;
    }

    private void setMainKeyboardFrame(
            @NonNull final SettingsValues settingsValues,
            @NonNull final KeyboardSwitchState toggleState) {
        PointerTracker.switchTo(mKeyboardView);
        final String deferred = mViewState.toTyping(isImeSuppressedByHardwareKeyboard(settingsValues, toggleState));
        applyViewState();
        mEmojiPalettesView.stopEmojiPalettes();
        mClipboardHistoryView.stopClipboardHistory();
        mAutocompleteView.stopAutocomplete();
        mHandwriteView.stopHandwrite();
        if (deferred != null) requestToolbarCheck("deferred while a panel was open: " + deferred);
    }

    /**
     * #843: the ONLY place that sets the visibility of the input view's pieces. Every
     * transition changes {@link #mViewState} and then renders its plan here, with the
     * settings of THIS moment - so a re-inflation or a locked config change can never leave
     * a panel rendered without the exit the plan guarantees.
     */
    private void applyViewState() {
        if (mCurrentInputView == null) return;
        final KeyboardViewState.Plan plan = mViewState.plan(new KeyboardViewState.Inputs(
                mLatinIME.hasSuggestionStripView(),
                Settings.getValues().mSecondaryStripVisible,
                mViewState.getImeSuppressed(),
                Settings.getInstance().readShowToolbarOnly()));
        // The visibility of {@link #mKeyboardView} must be aligned with {@link #MainKeyboardFrame}.
        // @see #getVisibleKeyboardView() and
        // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
        show(mMainKeyboardFrame, plan.shows(KeyboardViewState.Piece.MAIN_FRAME));
        show(mKeyboardViewWrapper, plan.shows(KeyboardViewState.Piece.KEYBOARD_WRAPPER));
        show(mKeyboardView, plan.shows(KeyboardViewState.Piece.MAIN_KEYBOARD));
        show(mStripContainer, plan.shows(KeyboardViewState.Piece.STRIP_CONTAINER));
        show(mSuggestionStripView, plan.shows(KeyboardViewState.Piece.SUGGESTION_STRIP));
        show(mEmojiTabStripView, plan.shows(KeyboardViewState.Piece.EMOJI_TAB_STRIP));
        show(mClipboardStripScrollView, plan.shows(KeyboardViewState.Piece.CLIPBOARD_STRIP));
        show(mEmojiPalettesView, plan.shows(KeyboardViewState.Piece.EMOJI_PALETTES));
        show(mClipboardHistoryView, plan.shows(KeyboardViewState.Piece.CLIPBOARD_HISTORY));
        show(mAutocompleteView, plan.shows(KeyboardViewState.Piece.AUTOCOMPLETE_VIEW));
        show(mHandwriteView, plan.shows(KeyboardViewState.Piece.HANDWRITE_VIEW));
        if (mViewState.isPanel() && plan.getExits().isEmpty())
            Log.e(TAG, "view state " + plan.getMode() + " rendered with NO exit - KeyboardViewState.plan is broken");
    }

    private static void show(@Nullable final View view, final boolean visible) {
        if (view != null) view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * #843: the emoji panel's Sticker/GIF body hides the emoji category strip. That strip is
     * one of the views {@link #applyViewState()} owns, so the panel asks here instead of
     * toggling it itself.
     */
    public void setEmojiCategoryStripShown(final boolean shown) {
        mViewState.showEmojiCategoryStrip(shown);
        applyViewState();
    }

    /** The current view state, for the debug API and logs. */
    @NonNull
    public KeyboardViewState.Mode getViewMode() {
        return mViewState.getMode();
    }

    /**
     * #843: leaves an open panel for typing - what the close key, the panel's toolbar key and
     * system back all do. Returns false (and does nothing) when no panel is open.
     */
    public boolean closePanelIfOpen(@NonNull final String why) {
        if (!mViewState.isPanel()) return false;
        Log.i(TAG, "closing " + mViewState.getMode() + ": " + why);
        setAlphabetKeyboard();
        if (mViewState.isPanel()) { // setKeyboard bailed out (no keyboard view yet): still leave
            final String deferred = mViewState.toTyping(false);
            applyViewState();
            if (deferred != null) requestToolbarCheck("deferred while a panel was open: " + deferred);
        }
        return true;
    }

    /**
     * #843: the #776 toolbar self-heal goes through here, never straight to the strip. While a
     * panel is open the check is deferred to the moment it closes; otherwise the strip checks
     * (and if needed rebuilds) its row, and the view state is re-rendered on top of it.
     */
    public void requestToolbarCheck(@NonNull final String trigger) {
        if (mSuggestionStripView == null || !mLatinIME.hasSuggestionStripView()) return;
        if (mViewState.deferToolbarCheck(trigger)) {
            Log.i(TAG, "toolbar check on " + trigger + " deferred: " + mViewState.getMode() + " is open");
            return;
        }
        mSuggestionStripView.ensureToolbar(trigger);
        applyViewState();
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setEmojiKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setEmojiKeyboard");
        }
        mViewState.openPanel(KeyboardViewState.PanelKind.EMOJI);
        mClipboardHistoryView.stopClipboardHistory();
        mAutocompleteView.stopAutocomplete();
        mHandwriteView.stopHandwrite();
        mEmojiPalettesView.startEmojiPalettes(mKeyboardView.getKeyVisualAttribute(),
                mLatinIME.getCurrentInputEditorInfo(), mLatinIME.mKeyboardActionListener);
        applyViewState();
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setClipboardKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setClipboardKeyboard");
        }
        mViewState.openPanel(KeyboardViewState.PanelKind.CLIPBOARD);
        mHandwriteView.stopHandwrite();
        mEmojiPalettesView.stopEmojiPalettes();
        mAutocompleteView.stopAutocomplete();
        // Builds the strip's keys (CLOSE_HISTORY always among them) from the settings of NOW.
        mClipboardHistoryView.startClipboardHistory(mLatinIME.getClipboardHistoryManager(), mKeyboardView.getKeyVisualAttribute(),
                mLatinIME.getCurrentInputEditorInfo(), mLatinIME.mKeyboardActionListener);
        applyViewState();
        mClipboardStripScrollView.post(() -> mClipboardStripScrollView.fullScroll(HorizontalScrollView.FOCUS_RIGHT));
    }

    // Implements {@link KeyboardState.SwitchActions}. #1103: the Autocomplete panel, its own view and engine.
    @Override
    public void setAutocompleteKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAutocompleteKeyboard");
        }
        mViewState.openPanel(KeyboardViewState.PanelKind.AUTOCOMPLETE);
        mHandwriteView.stopHandwrite();
        mEmojiPalettesView.stopEmojiPalettes();
        mClipboardHistoryView.stopClipboardHistory();
        mAutocompleteView.startAutocomplete(mKeyboardView.getKeyVisualAttribute(),
                mLatinIME.getCurrentInputEditorInfo(), mLatinIME.mKeyboardActionListener);
        applyViewState();
        mClipboardStripScrollView.post(() -> mClipboardStripScrollView.fullScroll(HorizontalScrollView.FOCUS_RIGHT));
    }

    // Implements {@link KeyboardState.SwitchActions}. #1781: the HandWrite panel, its own view and engine.
    @Override
    public void setHandwriteKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setHandwriteKeyboard");
        }
        mViewState.openPanel(KeyboardViewState.PanelKind.HANDWRITE);
        mEmojiPalettesView.stopEmojiPalettes();
        mClipboardHistoryView.stopClipboardHistory();
        mAutocompleteView.stopAutocomplete();
        mHandwriteView.startHandwrite();
        applyViewState();
    }

    @Override
    public void setNumpadKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setNumpadKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_NUMPAD, KeyboardSwitchState.OTHER);
    }

    @Override
    public void toggleNumpad(final boolean withSliding, final int autoCapsFlags,
            @Nullable final RecapitalizeMode recapitalizeMode, final boolean forceReturnToAlpha) {
        if (DEBUG_ACTION) {
            Log.d(TAG, "toggleNumpad");
        }
        mState.toggleNumpad(withSliding, autoCapsFlags, recapitalizeMode, forceReturnToAlpha, true);
    }

    public enum KeyboardSwitchState {
        HIDDEN(-1),
        SYMBOLS_SHIFTED(KeyboardId.ELEMENT_SYMBOLS_SHIFTED),
        EMOJI(KeyboardId.ELEMENT_EMOJI_RECENTS),
        CLIPBOARD(KeyboardId.ELEMENT_CLIPBOARD),
        OTHER(-1);

        final int mKeyboardId;

        KeyboardSwitchState(int keyboardId) {
            mKeyboardId = keyboardId;
        }
    }

    public KeyboardSwitchState getKeyboardSwitchState() {
        boolean hidden = !isShowingEmojiPalettes() && !isShowingClipboardHistory() && !isShowingAutocomplete() && !isShowingHandwrite()
                && (mKeyboardLayoutSet == null
                || mKeyboardView == null
                || !mKeyboardView.isShown());
        if (hidden) {
            return KeyboardSwitchState.HIDDEN;
        } else if (isShowingEmojiPalettes()) {
            return KeyboardSwitchState.EMOJI;
        } else if (isShowingClipboardHistory()) {
            return KeyboardSwitchState.CLIPBOARD;
        } else if (isShowingKeyboardId(KeyboardId.ELEMENT_SYMBOLS_SHIFTED)) {
            return KeyboardSwitchState.SYMBOLS_SHIFTED;
        }
        return KeyboardSwitchState.OTHER;
    }

    public void onToggleKeyboard(@NonNull final KeyboardSwitchState toggleState) {
        KeyboardSwitchState currentState = getKeyboardSwitchState();
        Log.w(TAG, "onToggleKeyboard() : Current = " + currentState + " : Toggle = " + toggleState
                + " : view state = " + mViewState.getMode());
        // #843: a panel's own toolbar key is one of its exits - pressed while that panel is
        // open it returns to typing. (Upstream hid the whole window here instead.)
        final KeyboardViewState.PanelKind kind = toggleState == KeyboardSwitchState.EMOJI ? KeyboardViewState.PanelKind.EMOJI
                : toggleState == KeyboardSwitchState.CLIPBOARD ? KeyboardViewState.PanelKind.CLIPBOARD : null;
        if (kind != null && mViewState.toggleTarget(kind) == KeyboardViewState.Mode.Typing.INSTANCE) {
            closePanelIfOpen("its toolbar key was pressed again");
        } else if (currentState == toggleState) {
            mLatinIME.stopShowingInputView();
            mLatinIME.hideWindow();
            setAlphabetKeyboard();
        } else {
            mLatinIME.startShowingInputView(true);
            if (kind == KeyboardViewState.PanelKind.EMOJI) {
                setEmojiKeyboard();
            } else if (kind == KeyboardViewState.PanelKind.CLIPBOARD) {
                setClipboardKeyboard();
            } else {
                setKeyboard(toggleState.mKeyboardId, toggleState);
            }
        }
        rememberPanelAfterManualSwitch();
    }

    // Future method for requesting an updating to the shift state.
    @Override
    public void requestUpdatingShiftState(final int autoCapsFlags, @Nullable final RecapitalizeMode recapitalizeMode) {
        if (DEBUG_ACTION) {
            Log.d(TAG, "requestUpdatingShiftState: "
                    + " autoCapsFlags=" + CapsModeUtils.flagsToString(autoCapsFlags)
                    + " recapitalizeMode=" + recapitalizeMode);
        }
        mState.onUpdateShiftState(autoCapsFlags, recapitalizeMode);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void startDoubleTapShiftKeyTimer() {
        if (DEBUG_TIMER_ACTION) {
            Log.d(TAG, "startDoubleTapShiftKeyTimer");
        }
        final MainKeyboardView keyboardView = getMainKeyboardView();
        if (keyboardView != null) {
            keyboardView.startDoubleTapShiftKeyTimer();
        }
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void cancelDoubleTapShiftKeyTimer() {
        if (DEBUG_TIMER_ACTION) {
            Log.d(TAG, "setAlphabetKeyboard");
        }
        final MainKeyboardView keyboardView = getMainKeyboardView();
        if (keyboardView != null) {
            keyboardView.cancelDoubleTapShiftKeyTimer();
        }
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setOneHandedModeEnabled(boolean enabled) {
        setOneHandedModeEnabled(enabled, false);
    }

    public void setOneHandedModeEnabled(boolean enabled, boolean force) {
        if (!force && mKeyboardViewWrapper.getOneHandedModeEnabled() == enabled) {
            return;
        }
        final Settings settings = Settings.getInstance();
        mKeyboardViewWrapper.setOneHandedModeEnabled(enabled);
        mKeyboardViewWrapper.setOneHandedGravity(settings.getCurrent().mOneHandedModeGravity);

        // oneHandeMode is always disabled when floating, and we shouldn't mess up the setting
        if (enabled != settings.getCurrent().mOneHandedModeEnabled)
            settings.writeOneHandedModeEnabled(enabled);
        reloadKeyboard();
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void switchOneHandedMode() {
        mKeyboardViewWrapper.switchOneHandedModeSide();
        Settings.getInstance().writeOneHandedModeGravity(mKeyboardViewWrapper.getOneHandedGravity());
    }

    @Override
    public void setFloatingKeyboardEnabled(boolean enabled) {
        if (enabled != Settings.getValues().mIsFloatingKeyboard)
            // mIsFloatingKeyboard is always disabled when device is locked, and we shouldn't mess up the setting
            SettingsKt.setFloatingKeyboardEnabled(mThemeContext, enabled);
        if (enabled) FloatingKeyboardUtils.setFloating(mCurrentInputView);
        else FloatingKeyboardUtils.disableFloating(mCurrentInputView);
        setBackgroundGatheringIndicatorPosition();
    }

    public void toggleSplitKeyboardMode() {
        final Settings settings = Settings.getInstance();
        settings.writeSplitKeyboardEnabled(
            !settings.getCurrent().mIsSplitKeyboardEnabled,
            mCurrentOrientation == Configuration.ORIENTATION_LANDSCAPE,
            FoldableUtils.INSTANCE.isFolded()
        );
        setOneHandedModeEnabled(settings.getCurrent().mOneHandedModeEnabled, true);
        reloadKeyboard();
    }

    public void reloadKeyboard() {
        if (mCurrentInputView == null)
            return;
        mEmojiPalettesView.clearKeyboardCache();
        reloadMainKeyboard();
    }

    public void reloadMainKeyboard() {
        // Reload the entire keyboard, and switch to the previous layout
        final boolean wasEmoji = mViewState.isPanel(KeyboardViewState.PanelKind.EMOJI);
        final boolean wasClipboard = mViewState.isPanel(KeyboardViewState.PanelKind.CLIPBOARD);
        final boolean wasAutocomplete = mViewState.isPanel(KeyboardViewState.PanelKind.AUTOCOMPLETE);
        final boolean wasHandwrite = mViewState.isPanel(KeyboardViewState.PanelKind.HANDWRITE);
        loadKeyboard(mLatinIME.getCurrentInputEditorInfo(), Settings.getValues(),
                mLatinIME.getCurrentAutoCapsState(), mLatinIME.getCurrentRecapitalizeState(), null);
        if (wasEmoji) {
            setEmojiKeyboard();
        } else if (wasClipboard) {
            setClipboardKeyboard();
        } else if (wasAutocomplete) {
            setAutocompleteKeyboard();
        } else if (wasHandwrite) {
            setHandwriteKeyboard();
        }
    }

    /**
     * Displays a toast message.
     *
     * @param text The text to display in the toast message.
     * @param briefToast If true, the toast duration will be short; otherwise, it will last longer.
     */
    public void showToast(final String text, final boolean briefToast){
        // In API 32 and below, toasts can be shown without a notification permission.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            final int toastLength = briefToast ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG;
            final Toast toast = Toast.makeText(mLatinIME, text, toastLength);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        } else {
            final int toastLength = briefToast ? 2000 : 3500;
            showFakeToast(text, toastLength);
        }
    }

    private static int getSecondaryStripVisibility() {
        return Settings.getValues().mSecondaryStripVisible? View.VISIBLE : View.GONE;
    }

    // Displays a toast-like message with the provided text for a specified duration.
    private void showFakeToast(final String text, final int timeMillis) {
        if (mFakeToastView.getVisibility() == View.VISIBLE) return;

        mFakeToastView.setText(text);
        mFakeToastView.setVisibility(View.VISIBLE);
        mFakeToastView.bringToFront();
        mFakeToastView.startAnimation(AnimationUtils.loadAnimation(mLatinIME, R.anim.fade_in));

        mFakeToastView.postDelayed(() -> {
            mFakeToastView.startAnimation(AnimationUtils.loadAnimation(mLatinIME, R.anim.fade_out));
            mFakeToastView.setVisibility(View.GONE);
        }, timeMillis);
    }

    public void setBackgroundGatheringIndicator(boolean enabled, boolean hasData, boolean saving) {
        if (mCurrentInputView == null) return;
        mBackgroundGatheringIndicator.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (!enabled) return;
        mBackgroundGatheringIndicator.setImageResource(hasData ? R.drawable.btn_keyboard_key_action_normal_lxx_base : R.drawable.ring);
        setBackgroundGatheringIndicatorPosition();
        if (!saving) return;
        mBackgroundGatheringIndicator.setImageTintList(ColorStateList.valueOf(0xff00a000));
        mBackgroundGatheringIndicator.postDelayed(() -> mBackgroundGatheringIndicator.setImageTintList(ColorStateList.valueOf(0xffa00000)), 1500);
    }

    private void setBackgroundGatheringIndicatorPosition() {
        if (mBackgroundGatheringIndicator == null || mBackgroundGatheringIndicator.getVisibility() != View.VISIBLE) return;
        if (mBackgroundGatheringIndicator.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margin) {
            Keyboard kb = mKeyboardView.getKeyboard();
            if (kb != null)
                margin.topMargin = kb.mOccupiedHeight - mBackgroundGatheringIndicator.getHeight();
            mBackgroundGatheringIndicator.setLayoutParams(mBackgroundGatheringIndicator.getLayoutParams());
        }
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public boolean isInDoubleTapShiftKeyTimeout() {
        if (DEBUG_TIMER_ACTION) {
            Log.d(TAG, "isInDoubleTapShiftKeyTimeout");
        }
        final MainKeyboardView keyboardView = getMainKeyboardView();
        return keyboardView != null && keyboardView.isInDoubleTapShiftKeyTimeout();
    }

    /**
     * Updates state machine to figure out when to automatically switch back to the previous mode.
     */
    public void onEvent(final Event event, final int currentAutoCapsState,
            @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onEvent(event, currentAutoCapsState, currentRecapitalizeState);
        final int code = event.isFunctionalKeyEvent() ? event.getKeyCode() : event.getCodePoint();
        if (code == KeyCode.ALPHA || code == KeyCode.CLIPBOARD || code == KeyCode.AUTOCOMPLETE || code == KeyCode.HANDWRITE || code == KeyCode.NUMPAD)
            rememberPanelAfterManualSwitch();
    }

    // Last-panel memory: only the user's own switches write it (never a programmatic reset or restore).
    private LastPanelMemory mLastPanelMemory;

    private LastPanelMemory lastPanelMemory() {
        if (mLastPanelMemory == null) mLastPanelMemory = new LastPanelMemory(KtxKt.prefs(mLatinIME));
        return mLastPanelMemory;
    }

    private void rememberPanelAfterManualSwitch() {
        final LastPanelMemory.Panel panel;
        if (mViewState.isPanel(KeyboardViewState.PanelKind.CLIPBOARD)) panel = LastPanelMemory.Panel.CLIPBOARD;
        else if (mViewState.isPanel(KeyboardViewState.PanelKind.AUTOCOMPLETE)) panel = LastPanelMemory.Panel.AUTOCOMPLETE;
        else if (mViewState.isPanel(KeyboardViewState.PanelKind.HANDWRITE)) panel = LastPanelMemory.Panel.HANDWRITE;
        else if (mViewState.isPanel()) return; // emoji: not a remembered panel
        else if (isShowingKeyboardId(KeyboardId.ELEMENT_NUMPAD)) panel = LastPanelMemory.Panel.NUMPAD;
        else if (isShowingKeyboardId(KeyboardId.ELEMENT_ALPHABET, KeyboardId.ELEMENT_ALPHABET_MANUAL_SHIFTED,
                KeyboardId.ELEMENT_ALPHABET_AUTOMATIC_SHIFTED, KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCKED,
                KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCK_SHIFTED)) panel = LastPanelMemory.Panel.LETTERS;
        else return; // symbols: not a remembered panel
        lastPanelMemory().remember(panel);
    }

    /** Reopens the remembered panel on a fresh input start; a numeric/phone/password field keeps its own layout. */
    public void restoreLastPanel(@NonNull final EditorInfo editorInfo, final int autoCapsFlags,
            @Nullable final RecapitalizeMode recapitalizeMode) {
        final LastPanelMemory.Panel panel = lastPanelMemory().panelToRestore(editorInfo.inputType);
        if (panel == null || panel == LastPanelMemory.Panel.LETTERS) return;
        final int code = panel == LastPanelMemory.Panel.NUMPAD ? KeyCode.NUMPAD
                : panel == LastPanelMemory.Panel.CLIPBOARD ? KeyCode.CLIPBOARD
                : panel == LastPanelMemory.Panel.HANDWRITE ? KeyCode.HANDWRITE : KeyCode.AUTOCOMPLETE;
        // through the state machine, but not through onEvent() above: a restore is not a manual switch
        mState.onEvent(Event.createSoftwareKeypressEvent(code, 0, 0, 0, false), autoCapsFlags, recapitalizeMode);
    }

    public boolean isShowingKeyboardId(@NonNull int... keyboardIds) {
        if (mKeyboardView == null || !mKeyboardView.isShown()) {
            return false;
        }
        final Keyboard keyboard = mKeyboardView.getKeyboard();
        if (keyboard == null) // may happen when using hardware keyboard
            return false;
        int activeKeyboardId = keyboard.mId.mElementId;
        for (int keyboardId : keyboardIds) {
            if (activeKeyboardId == keyboardId) {
                return true;
            }
        }
        return false;
    }

    public boolean isShowingEmojiPalettes() {
        return mEmojiPalettesView != null && mEmojiPalettesView.isShown();
    }

    public boolean isShowingClipboardHistory() {
        return mClipboardHistoryView != null && mClipboardHistoryView.isShown();
    }

    public HandwriteView getHandwriteView() {
        return mHandwriteView;
    }

    public AutocompleteView getAutocompleteView() {
        return mAutocompleteView;
    }

    public boolean isShowingHandwrite() {
        return mHandwriteView != null && mHandwriteView.isShown();
    }

    public boolean isShowingAutocomplete() {
        return mAutocompleteView != null && mAutocompleteView.isShown();
    }

    public boolean isShowingPopupKeysPanel() {
        if (isShowingEmojiPalettes() || isShowingClipboardHistory() || isShowingAutocomplete()) {
            return false;
        }
        return mKeyboardView.isShowingPopupKeysPanel();
    }

    public boolean isShowingStripContainer() {
        return mStripContainer.isShown();
    }

    public EmojiPalettesView getEmojiPalettesView() {
        return mEmojiPalettesView;
    }

    public View getVisibleKeyboardView() {
        if (isShowingEmojiPalettes()) {
            return mEmojiPalettesView;
        } else if (isShowingClipboardHistory()) {
            return mClipboardHistoryView;
        } else if (isShowingAutocomplete()) {
            return mAutocompleteView;
        } else if (isShowingHandwrite()) {
            return mHandwriteView;
        }
        return mKeyboardView;
    }

    public View getWrapperView() {
        return mKeyboardViewWrapper;
    }

    public View getEmojiTabStrip() {
        return mEmojiTabStripView;
    }

    public LinearLayout getClipboardStrip() {
        return mClipboardStripView;
    }

    public MainKeyboardView getMainKeyboardView() {
        return mKeyboardView;
    }

    public FrameLayout getStripContainer() { return mStripContainer; }

    public void deallocateMemory() {
        if (mKeyboardView != null) {
            mKeyboardView.cancelAllOngoingEvents();
            mKeyboardView.deallocateMemory();
        }
        if (mEmojiPalettesView != null) {
            mEmojiPalettesView.stopEmojiPalettes();
        }
        if (mClipboardHistoryView != null) {
            mClipboardHistoryView.stopClipboardHistory();
        }
        if (mAutocompleteView != null) {
            mAutocompleteView.stopAutocomplete();
        }
        if (mHandwriteView != null) {
            mHandwriteView.stopHandwrite();
        }
    }

    public void trimMemory() {
        if (mEmojiPalettesView != null) {
            mEmojiPalettesView.clearKeyboardCache();
        }
    }

    @SuppressLint("InflateParams")
    public View onCreateInputView(@NonNull Context displayContext, final boolean isHardwareAcceleratedDrawingEnabled) {
        if (mKeyboardView != null) {
            mKeyboardView.closing();
        }
        PointerTracker.clearOldViewData();
        final SharedPreferences prefs = KtxKt.prefs(displayContext);
        if (mSuggestionStripView != null)
            prefs.unregisterOnSharedPreferenceChangeListener(mSuggestionStripView);
        if (mClipboardHistoryView != null)
            prefs.unregisterOnSharedPreferenceChangeListener(mClipboardHistoryView);
        if (mThemeNeedsReload) // necessary in some cases (e.g. theme switch) when mThemeNeedsReload is set before first keyboard load
            Settings.getInstance().loadSettings(displayContext, Settings.getValues().mLocale, Settings.getValues().mInputAttributes);

        updateKeyboardThemeAndContextThemeWrapper(displayContext, KeyboardTheme.getKeyboardTheme(displayContext));
        mCurrentInputView = (InputView)LayoutInflater.from(mThemeContext).inflate(R.layout.input_view, null);
        // #843: the fresh views are the typing layout; the model starts there too.
        mViewState.onInputViewRecreated();
        mMainKeyboardFrame = mCurrentInputView.findViewById(R.id.main_keyboard_frame);
        mEmojiPalettesView = mCurrentInputView.findViewById(R.id.emoji_palettes_view);
        mClipboardHistoryView = mCurrentInputView.findViewById(R.id.clipboard_history_view);
        mAutocompleteView = mCurrentInputView.findViewById(R.id.autocomplete_view);
        mHandwriteView = mCurrentInputView.findViewById(R.id.handwrite_view);
        mFakeToastView = mCurrentInputView.findViewById(R.id.fakeToast);

        mKeyboardViewWrapper = mCurrentInputView.findViewById(R.id.keyboard_view_wrapper);
        mKeyboardViewWrapper.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mKeyboardView = mCurrentInputView.findViewById(R.id.keyboard_view);
        mKeyboardView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        mKeyboardView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mEmojiPalettesView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        mEmojiPalettesView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mClipboardHistoryView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        mClipboardHistoryView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mAutocompleteView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        mAutocompleteView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mHandwriteView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mHandwriteView.setTextBeforeCursor(() -> {
            final android.view.inputmethod.InputConnection ic = mLatinIME.getCurrentInputConnection();
            final CharSequence before = ic == null ? null : ic.getTextBeforeCursor(30, 0);
            return before == null ? "" : before.toString();
        });
        mEmojiTabStripView = mCurrentInputView.findViewById(R.id.emoji_tab_strip);
        mClipboardStripView = mCurrentInputView.findViewById(R.id.clipboard_strip);
        mClipboardStripScrollView = mCurrentInputView.findViewById(R.id.clipboard_strip_scroll_view);
        mSuggestionStripView = mCurrentInputView.findViewById(R.id.suggestion_strip_view);
        mStripContainer = mCurrentInputView.findViewById(R.id.strip_container);
        mBackgroundGatheringIndicator = mCurrentInputView.findViewById(R.id.backgroundGatheringIndicator);

        prefs.registerOnSharedPreferenceChangeListener(mSuggestionStripView);
        prefs.registerOnSharedPreferenceChangeListener(mClipboardHistoryView);
        PointerTracker.switchTo(mKeyboardView);
        return mCurrentInputView;
    }

    public int getKeyboardShiftMode() {
        final Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            return WordComposer.CAPS_MODE_OFF;
        }
        return keyboard.mId.getCapsMode();
    }

    public String getCurrentKeyboardScript() {
        if (null == mKeyboardLayoutSet) {
            return ScriptUtils.SCRIPT_UNKNOWN;
        }
        return mKeyboardLayoutSet.getScript();
    }

    public void switchToSubtype(InputMethodSubtype subtype) {
        mLatinIME.switchToSubtype(subtype);
    }

    // used for debug
    public String getLocaleAndConfidenceInfo() {
        return mLatinIME.getLocaleAndConfidenceInfo();
    }

    /** Marks the theme as outdated. The theme will be reloaded next time the keyboard is shown.
     *  If the keyboard is currently showing, theme will be reloaded immediately. */
    public void setThemeNeedsReload() {
        mThemeNeedsReload = true;
        if (mLatinIME == null || !mLatinIME.isInputViewShown())
            return; // will be reloaded right before showing IME

        // Hide and show IME, showing will trigger the reload.
        // Reloading while IME is shown is glitchy, and hiding / showing is so fast the user shouldn't notice.
        mLatinIME.hideWindow();
        try {
            mLatinIME.showWindow(true);
        } catch (IllegalStateException e) {
            // in tests isInputViewShown returns true, but showWindow throws "IllegalStateException: Window token is not set yet."
        }
    }
}

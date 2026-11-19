// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.compat.ClipboardManagerCompat
import helium314.keyboard.event.Event
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.isValidNumber
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ClipboardHistoryManager(
        private val latinIME: LatinIME
) : ClipboardManager.OnPrimaryClipChangedListener, ClipboardDao.Listener {

    fun interface HistoryChangeListener {
        fun onHistoryChanged()
    }

    private lateinit var clipboardManager: ClipboardManager
    private var clipboardSuggestionView: View? = null
    private var clipboardDao: ClipboardDao? = null
    private var tempPrimaryClip = false

    // null = default (unpinned) page; otherwise the pin list currently shown
    private var currentList: String? = null
    private var historyChangeListener: HistoryChangeListener? = null

    fun onCreate() {
        clipboardManager = latinIME.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(this)
        clipboardDao = ClipboardDao.getInstance(latinIME)
        if (latinIME.mSettings.current.mClipboardHistoryEnabled)
            fetchPrimaryClip()
    }

    fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(this)
    }

    override fun onPrimaryClipChanged() {
        // Make sure we read clipboard content only if history settings is set
        if (latinIME.mSettings.current.mClipboardHistoryEnabled) {
            fetchPrimaryClip()
            dontShowCurrentSuggestion = false
        }
    }

    // todo for later
    //  setting whether to store sensitive clip data?
    //  care about other clip items than first?
    private fun fetchPrimaryClip() {
        if (tempPrimaryClip) return // avoid updating history
        val clipData = clipboardManager.primaryClip ?: return
        if (clipData.itemCount == 0) return
        val clipItem = clipData.getItemAt(0) ?: return
        val description = clipData.description ?: return
        // #1077: a clip marked sensitive (EXTRA_IS_SENSITIVE: a password manager or any app that copies a secret) never enters history
        if (ClipboardManagerCompat.getClipSensitivity(description) == true) return
        val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)

        if (description.hasMimeType("text/*")) {
            val content = clipItem.coerceToText(latinIME)
            if (TextUtils.isEmpty(content)) return
            val keepHtml = latinIME.prefs().getBoolean(ClipboardHtml.PREF_KEEP_HTML, ClipboardHtml.DEFAULT_KEEP_HTML)
            val html = ClipboardHtml.htmlToStore(keepHtml, clipItem.htmlText)
            clipboardDao?.addClip(timeStamp, false, content.toString(), html)
        } else if (maySaveFromUri(clipItem.uri, latinIME)) {
            clipboardDao?.addClipUri(timeStamp, false, clipItem.uri, description, latinIME)
        }
    }

    // fallback method because in some apps there is no supported mime type and commitContend does nothing,
    // but KeyEvent.KEYCODE_PASTE for pasting from primary clip works fine
    // (actually we do change the primary clip, but (try to) revert immediately)
    fun pasteWithoutChangingClips(content: InputContentInfoCompat) {
        Log.d(TAG, "trying fallback pasting with system clipboard")
        val primaryClip = clipboardManager.primaryClip
        val tempClip = ClipData(content.description, ClipData.Item(content.contentUri))
        tempPrimaryClip = true
        clipboardManager.setPrimaryClip(tempClip)
        latinIME.onEvent(Event.createSoftwareKeypressEvent(KeyCode.CLIPBOARD_PASTE, 0,
            Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false))
        tempPrimaryClip = false
        if (primaryClip == null)
            return
        // we need to wait a little before switching back to the original primary clip
        // a. it can happen that we switch back before the pasting has started, in that case we only past the primary clip
        // b. if we switch while the clip is pasted, it might crash the app (tested with joplin and logseq)
        // todo: replacing the current primary clip is far from ideal, try finding a different way
        GlobalScope.launch {
            delay(500)
            try {
                clipboardManager.setPrimaryClip(primaryClip)
            } catch (e: Exception) {
                Log.i(TAG, "could not go back to old primary clip", e)
                // happens wen the clip was a file
                // try to find it in out clipboard entries
                val clip = clipboardDao?.getAll()?.firstOrNull { it.timeStamp == ClipboardManagerCompat.getClipTimestamp(primaryClip) }
                if (clip?.filename != null)
                    clipboardManager.setPrimaryClip(ClipData(
                        ClipDescription(clip.text, clip.mimeTypes?.toTypedArray()),
                        ClipData.Item(clip.getContentUri(latinIME))
                    ))
                else if (clip != null)
                    clipboardManager.setPrimaryClip(ClipData(
                        ClipDescription("", arrayOf("text/*")),
                        ClipData.Item(clip.text)
                    ))
            }
        }
    }

    /**
     * #1813 Tap on an entry: HTML clip plus the target app's own paste when the entry has HTML and
     * "Keep HTML formatting" is on, otherwise (and returned as false) the caller commits the text as before.
     */
    fun pasteEntryAsHtml(entry: ClipboardHistoryEntry): Boolean {
        val keepHtml = latinIME.prefs().getBoolean(ClipboardHtml.PREF_KEEP_HTML, ClipboardHtml.DEFAULT_KEEP_HTML)
        if (ClipboardHtml.pasteMode(keepHtml, entry.html, entry.filename != null) != ClipboardHtml.PasteMode.HTML_CLIP_THEN_PASTE) return false
        val html = entry.html ?: return false
        val primaryClip = clipboardManager.primaryClip
        tempPrimaryClip = true // same as pasteWithoutChangingClips: the temporary clip must not re-enter history
        try {
            clipboardManager.setPrimaryClip(ClipData.newHtmlText("", entry.text ?: "", html))
        } catch (e: Exception) {
            Log.w(TAG, "could not set html clip, pasting plain text", e)
            tempPrimaryClip = false
            return false
        }
        val pasted = latinIME.currentInputConnection?.performContextMenuAction(android.R.id.paste) == true
        if (!pasted)
            latinIME.onEvent(Event.createSoftwareKeypressEvent(KeyCode.CLIPBOARD_PASTE, 0,
                Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false))
        GlobalScope.launch {
            delay(500) // let the target app read the clip before the original one is put back
            try {
                if (primaryClip != null) clipboardManager.setPrimaryClip(primaryClip)
            } catch (e: Exception) {
                Log.i(TAG, "could not go back to old primary clip", e)
            }
            tempPrimaryClip = false
        }
        return true
    }

    // Live search query — empty string means "no filter". Updated by the search EditText in ClipboardHistoryView.
    var searchQuery: String = ""

    // entries for the page currently shown (default unpinned page, or one pin list),
    // further filtered by [searchQuery] when non-blank (search is within-current-tab)
    private fun filteredEntries(): List<ClipboardHistoryEntry> {
        val list = clipboardDao?.getForList(currentList) ?: emptyList()
        val q = searchQuery
        return if (q.isEmpty()) list else list.filter { it.text?.contains(q, ignoreCase = true) == true }
    }

    fun getCurrentList() = currentList

    fun setCurrentList(listName: String?) {
        currentList = listName
    }

    fun getListNames(): List<String> = clipboardDao?.getListNames() ?: emptyList()

    /**
     * The "new list" entry of the pin picker (long-press on an unpinned clip; the picker itself is
     * drawn by the clipboard panel): copies [id] into the next freshly-numbered list. The clip stays
     * in the history — the default page is the full record of what was copied, a pin list is a
     * durable copy of part of it.
     */
    fun pinToNewList(id: Long) {
        pinToList(id, clipboardDao?.nextListName() ?: return)
    }

    fun pinToList(id: Long, listName: String) {
        clipboardDao?.pinToList(id, listName)
        historyChangeListener?.onHistoryChanged()
    }

    /**
     * Long-press on an already-pinned clip (only reachable from within its own pin list tab):
     * drop the copy this list holds. The history page is unaffected — pinning copied the clip
     * into the list, it never moved it out of the history.
     */
    fun unpin(id: Long) {
        clipboardDao?.unpin(id)
        historyChangeListener?.onHistoryChanged()
    }

    /**
     * Rename pin list [oldName] to [newName].
     * Delegates guard logic (blank / collision) to ClipboardDao.
     * If the currently-viewed list was renamed the caller is responsible for calling
     * setCurrentList(newName) before (or after) this, then refreshing the adapter.
     */
    fun renameList(oldName: String, newName: String) {
        clipboardDao?.renameList(oldName, newName)
        historyChangeListener?.onHistoryChanged()
    }

    fun clearHistory() {
        clipboardDao?.clearNonPinned()
        ClipboardManagerCompat.clearPrimaryClip(clipboardManager)
        removeClipboardSuggestion()
    }

    fun canRemove(index: Int) = filteredEntries().getOrNull(index)?.isPinned == false

    fun removeEntry(index: Int) {
        val entry = filteredEntries().getOrNull(index) ?: return
        if (!entry.isPinned) clipboardDao?.deleteClip(entry.id)
    }

    fun sortHistoryEntries() {
        clipboardDao?.sort()
    }

    // We do not want to update history while user is visualizing it, so we check retention only
    // when history is about to be shown
    fun prepareClipboardHistory() = clipboardDao?.clearOldClips(true)

    fun getHistorySize() = filteredEntries().size

    fun getHistoryEntry(position: Int) = filteredEntries().getOrNull(position)

    fun getHistoryEntryContent(id: Long) = clipboardDao?.get(id)

    fun setHistoryChangeListener(listener: HistoryChangeListener?) {
        historyChangeListener = listener
        // dao.listener != null also means "history view is visible" (blocks retention cleanup), see clearOldClips
        clipboardDao?.listener = if (listener != null) this else null
    }

    override fun onClipInserted(position: Int) = historyChangeListener?.onHistoryChanged() ?: Unit
    override fun onClipsRemoved(position: Int, count: Int) = historyChangeListener?.onHistoryChanged() ?: Unit
    override fun onClipMoved(oldPosition: Int, newPosition: Int) = historyChangeListener?.onHistoryChanged() ?: Unit

    private fun isClipSensitive(inputType: Int): Boolean {
        ClipboardManagerCompat.getClipSensitivity(clipboardManager.primaryClip?.description)?.let { return it }
        return InputTypeUtils.isPasswordInputType(inputType)
    }

    fun getClipboardSuggestionView(editorInfo: EditorInfo?, parent: ViewGroup?): View? {
        // maybe no need to create a new view
        // but a cache has to consider a few possible changes, so better don't implement without need
        clipboardSuggestionView = null

        // get the content, or return null
        if (!latinIME.mSettings.current.mSuggestClipboardContent) return null
        if (dontShowCurrentSuggestion) return null
        if (parent == null) return null
        val clipData = clipboardManager.primaryClip ?: return null
        if (clipData.itemCount == 0) return null
        val clipItem = clipData.getItemAt(0) ?: return null
        val hasText = clipData.description?.hasMimeType("text/*") == true
        val hasImage = clipData.description?.hasMimeType("image/*") == true && clipItem.uri != null
        if (!hasText && !hasImage) return null
        val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)
        if (System.currentTimeMillis() - timeStamp > RECENT_TIME_MILLIS) return null
        val content = clipItem.coerceToText(latinIME)

        // create the view
        val clipIcon = latinIME.mKeyboardSwitcher.keyboard.mIconsSet.getIconDrawable(ToolbarKey.PASTE.name.lowercase())
        val inputType = editorInfo?.inputType ?: InputType.TYPE_NULL
        var shownText: CharSequence? = null
        if (hasText) {
            if (TextUtils.isEmpty(content)) return null
            if (InputTypeUtils.isNumberInputType(inputType) && !content.isValidNumber()) return null
            shownText = if (isClipSensitive(inputType)) "*".repeat(content.length.coerceAtMost(200)) else content
        }
        var thumbnail: Bitmap? = null
        if (hasImage) {
            if (InputTypeUtils.isNumberInputType(inputType)) return null
            val loaded = try {
                loadThumbnail(clipItem.uri)
            } catch (e: Exception) {
                Log.w(TAG, "error setting clipboard image", e) // happens with SecurityException: Permission Denial
                null
            }
            thumbnail = loaded ?: return null
        }

        val colors = latinIME.mSettings.current.mColors
        clipIcon?.let { colors.setColor(it, ColorType.KEY_ICON) }
        val closeIcon = latinIME.mKeyboardSwitcher.keyboard.mIconsSet.getIconDrawable(ToolbarKey.CLOSE_HISTORY.name.lowercase())
        closeIcon?.let { colors.setColor(it, ColorType.REMOVE_SUGGESTION_ICON) }

        val chip = ClipboardSuggestionChip(latinIME, shownText, thumbnail, clipIcon, closeIcon,
            onPaste = { view ->
                dontShowCurrentSuggestion = true
                if (hasText) latinIME.onTextInput(content.toString())
                else latinIME.onEvent(Event.createSoftwareKeypressEvent(KeyCode.CLIPBOARD_PASTE, 0,
                    Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false))
                AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
                view.isGone = true
            },
            onClose = { removeClipboardSuggestion() })
        clipboardSuggestionView = chip
        return chip
    }

    /** The clip's image, decoded at thumbnail size; the strip row is a few dozen dp tall. */
    private fun loadThumbnail(uri: Uri?): Bitmap? {
        if (uri == null) return null
        val resolver = latinIME.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val target = 256
        val sample = (maxOf(bounds.outWidth, bounds.outHeight) / target).coerceAtLeast(1)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun removeClipboardSuggestion() {
        dontShowCurrentSuggestion = true
        val csv = clipboardSuggestionView ?: return
        if (csv.parent != null && !csv.isGone) {
            // clipboard view is shown ->
            latinIME.setNeutralSuggestionStrip()
            latinIME.mHandler.postResumeSuggestions(false)
        }
        csv.isGone = true
    }

    companion object {
        private val TAG = "ClipboardHistoryManager"

        // avoid showing the current suggestion because it has been dismissed or pasted
        private var dontShowCurrentSuggestion: Boolean = false

        const val RECENT_TIME_MILLIS = 3 * 60 * 1000L // 3 minutes (for clipboard suggestions)

        private fun maySaveFromUri(uri: Uri?, context: Context): Boolean {
            val maxSize = context.prefs().getInt(Settings.PREF_CLIPBOARD_FILES_SIZE_LIMIT, Defaults.PREF_CLIPBOARD_FILES_SIZE_LIMIT)
            val saveUriData = context.prefs().getBoolean(Settings.PREF_CLIPBOARD_USE_FILES, Defaults.PREF_CLIPBOARD_USE_FILES)
            if (uri == null || !saveUriData) return false
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null).use {
                    if (it?.moveToFirst() != true) return false
                    val size = it.getLong(0)
                    return size <= maxSize * 1000000 // maxSize is megabytes
                }
            } catch (e: Exception) {
                Log.w(TAG, "error checking clip size", e) // happens with SecurityException: Permission Denial
                return false
            }
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.content.ClipDescription
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.content.FileProvider
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import androidx.core.view.inputmethod.InputContentInfoCompat
import helium314.keyboard.latin.database.ClipboardDao
import java.io.File

class ClipboardHistoryEntry(
    val id: Long,
    var timeStamp: Long,
    var listName: String?,
    val text: String?,
    val filename: String?,
    val mimeTypes: List<String>?,
    var html: String? = null // #1813 the HTML half of a rich clip, null for plain text
) : Comparable<ClipboardHistoryEntry> {
    // null = default (unpinned) page; non-null = the pin list this clip belongs to
    val isPinned: Boolean get() = listName != null

    // every displayed page is filtered to a single list already, so plain recency ordering is enough
    override fun compareTo(other: ClipboardHistoryEntry): Int = other.timeStamp.compareTo(timeStamp)

    fun getContentInfo(context: Context): InputContentInfoCompat =
        InputContentInfoCompat(getContentUri(context)!!, ClipDescription(text, mimeTypes?.toTypedArray()), null)

    fun getContentUri(context: Context) = filename?.let { FileProvider.getUriForFile(
        context,
        context.getString(R.string.clipboard_provider_authority),
        File(ClipboardDao.clipFilesDir, it)
    ) }

    /** What a file clip shows in the panel: its decoded image, or a type icon plus a description. */
    class Preview(val image: Bitmap?, val icon: Bitmap?, val text: String?)

    // todo: if slow we could decode images it in a coroutine, or use cached preview images
    fun loadPreview(context: Context): Preview? {
        if (mimeTypes == null || filename == null) return null // should never happen
        try {
            val path = File(ClipboardDao.clipFilesDir, filename).absolutePath
            val opt = BitmapFactory.Options()
            opt.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, opt)
            // reduce size of images larger than the screen, only needs to fit half screen width
            val scale = opt.outWidth / (context.resources.displayMetrics.widthPixels * 2)
            opt.inSampleSize = scale
            opt.inJustDecodeBounds = false
            val bitmap = BitmapFactory.decodeFile(path, opt)
            if (bitmap != null) return Preview(bitmap, null, null)
        } catch (e: Exception) {
            Log.w("ClipboardHistoryEntry", "could not load image for clip $id", e)
        }
        val description = if (text.isNullOrBlank()) ""
            else "\n" + context.getString(R.string.item_description, text)
        val tint = Settings.getValues().mColors.get(ColorType.EMOJI_CATEGORY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val info = context.contentResolver.getTypeInfo(mimeTypes[0])
            val drawable = info.icon.loadDrawable(context)?.mutate()
            drawable?.setTint(tint)
            return Preview(null, drawable?.toBitmap(),
                context.getString(R.string.item_type, info.label.toString()) + description)
        }
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_dictionary)?.mutate()
        drawable?.setTint(tint)
        return Preview(null, drawable?.toBitmap(), context.getString(R.string.item_type, mimeTypes.first()) + description)
    }
}

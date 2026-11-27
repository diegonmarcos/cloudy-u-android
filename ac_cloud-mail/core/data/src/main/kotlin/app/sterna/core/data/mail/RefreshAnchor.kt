package app.sterna.core.data.mail

import androidx.paging.PagingSource
import androidx.paging.PagingState

/**
 * Where a refresh must reload the list, in rows from the top of the folder. With placeholders off
 */
internal fun refreshOffsetForAnchor(
    anchorPosition: Int?,
    firstLoadedItemsBefore: Int,
    initialLoadSize: Int,
    placeholdersEnabled: Boolean,
): Int? {
    if (anchorPosition == null) return null
    val windowStart = if (placeholdersEnabled) 0 else firstLoadedItemsBefore.coerceAtLeast(0)
    return (windowStart + anchorPosition - initialLoadSize / 2).coerceAtLeast(0)
}

/** Loads exactly what [delegate] loads but answers [refreshOffsetForAnchor]'s key: the key is the
 *  only thing wrong with Room's generated `LimitOffsetPagingSource`, which cannot be subclassed per
 *  query. Invalidation is forwarded both ways, or one side serves a generation the other dropped. */
internal class AnchoredRefreshPagingSource<Value : Any>(
    private val delegate: PagingSource<Int, Value>,
) : PagingSource<Int, Value>() {

    init {
        delegate.registerInvalidatedCallback(::invalidate)
        registerInvalidatedCallback(delegate::invalidate)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Value> {
        // #1952: the list's own stopwatch for the store query (logcat tag MailList, sizes only), read by the emulator walk.
        val t0 = System.nanoTime()
        val result = delegate.load(params)
        runCatching {
            android.util.Log.i(
                "MailList",
                "page-load kind=${params.javaClass.simpleName} ms=${(System.nanoTime() - t0) / 1_000_000} rows=${(result as? LoadResult.Page)?.data?.size ?: -1}",
            )
        }
        return result
    }

    override fun getRefreshKey(state: PagingState<Int, Value>): Int? = refreshOffsetForAnchor(
        anchorPosition = state.anchorPosition,
        firstLoadedItemsBefore = state.pages.firstOrNull()?.itemsBefore ?: 0,
        initialLoadSize = state.config.initialLoadSize,
        placeholdersEnabled = state.config.enablePlaceholders,
    )

    override val jumpingSupported: Boolean get() = delegate.jumpingSupported

    override val keyReuseSupported: Boolean get() = delegate.keyReuseSupported
}

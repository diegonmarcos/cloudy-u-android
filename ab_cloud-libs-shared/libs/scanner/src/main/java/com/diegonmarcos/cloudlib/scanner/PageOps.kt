package com.diegonmarcos.cloudlib.scanner

/** #1908 the page operations of the review strip, pure and generic: every one returns a NEW list and ignores an index that is not there. */
object PageOps {
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices) return list
        val t = to.coerceIn(0, list.lastIndex)
        return list.toMutableList().also { it.add(t, it.removeAt(from)) }
    }

    fun <T> delete(list: List<T>, index: Int): List<T> = if (index in list.indices) list.filterIndexed { i, _ -> i != index } else list

    /** A copy of the page at [index] right after it ([copy] makes the new page, e.g. a new id). */
    fun <T> duplicate(list: List<T>, index: Int, copy: (T) -> T): List<T> =
        if (index in list.indices) list.toMutableList().also { it.add(index + 1, copy(list[index])) } else list

    fun <T> insert(list: List<T>, index: Int, item: T): List<T> = list.toMutableList().also { it.add(index.coerceIn(0, it.size), item) }

    fun <T> replace(list: List<T>, index: Int, item: T): List<T> = if (index in list.indices) list.toMutableList().also { it[index] = item } else list

    /** Pages before [at] and pages from [at] on: a scan cut into two documents. */
    fun <T> split(list: List<T>, at: Int): Pair<List<T>, List<T>> {
        val k = at.coerceIn(0, list.size)
        return list.subList(0, k).toList() to list.subList(k, list.size).toList()
    }

    fun <T> merge(a: List<T>, b: List<T>): List<T> = a + b
}

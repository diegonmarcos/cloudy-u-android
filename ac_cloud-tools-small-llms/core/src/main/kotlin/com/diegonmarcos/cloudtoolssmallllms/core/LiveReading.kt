package com.diegonmarcos.cloudtoolssmallllms.core

/** Registers ONE sensor listener at the UI rate; [register] is false when the platform refuses (the app adapts SensorManager to this). */
interface SensorRegistrar {
    fun register(sensorId: Int, onValues: (FloatArray) -> Unit): Boolean
    fun unregister()
}

/**
 * The live reading of one sensor: a listener exists only between [show] and [hide] (the page calls them as the row appears and
 * as it is hidden), never in the background. Keeps the last [history] samples for the graph.
 */
class LiveReading(private val registrar: SensorRegistrar, private val history: Int, private val onChange: () -> Unit = {}) {
    /** The sensor being listened to; null when nothing is registered. */
    var sensorId: Int? = null
        private set
    /** False when the last [show] was refused by the platform. */
    var supported: Boolean = true
        private set
    var last: FloatArray = FloatArray(0)
        private set
    private val ring = ArrayDeque<FloatArray>()

    val samples: List<FloatArray> get() = ring.toList()

    fun show(id: Int) {
        hide()
        supported = registrar.register(id) { push(it) }
        if (supported) sensorId = id
        onChange()
    }

    fun hide() {
        if (sensorId == null) return
        registrar.unregister()
        sensorId = null
        ring.clear()
        last = FloatArray(0)
    }

    private fun push(v: FloatArray) {
        if (sensorId == null) return
        last = v.copyOf()
        ring.addLast(last)
        while (ring.size > history) ring.removeFirst()
        onChange()
    }

    /** The min and max over the first [series] values of every sample (a flat line gets a unit span), for the graph's scale. */
    fun range(series: Int): Pair<Float, Float> {
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (s in ring) for (i in 0 until minOf(series, s.size)) { lo = minOf(lo, s[i]); hi = maxOf(hi, s[i]) }
        if (lo > hi) return 0f to 1f
        return if (hi - lo < 1e-6f) (lo - 0.5f) to (hi + 0.5f) else lo to hi
    }
}

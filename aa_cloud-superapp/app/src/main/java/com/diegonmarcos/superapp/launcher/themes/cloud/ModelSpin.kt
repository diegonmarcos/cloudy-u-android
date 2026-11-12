package com.diegonmarcos.superapp.launcher.themes.cloud

/**
 * #1685 The home model's orientation, as ACCUMULATED angles instead of a pure
 * function of the clock. Before, the 3D axes were either `angleX/Y(elapsed)` or
 * (drag-to-spin on) frozen at the user's drag, while the two 4D planes always
 * ran off the clock: so the 4D model always turned and the 3D one never did.
 *
 * Now one state carries all four angles (3D x/y, 4D xw/yz) plus the drag
 * momentum. [step] advances it by the real frame delta:
 *  - dragging: the user owns the 3D axes (the drag handler writes rotX/rotY); nothing auto-advances;
 *  - released: the momentum coasts and decays exactly as before (0.95 per frame);
 *  - auto-rotate on: every angle advances at its declared period FROM WHERE IT IS
 *    (the user's orientation), so there is no snap back;
 *  - auto-rotate off: the angles stay where the user left them.
 * The initial state equals the old `elapsed = 0` frame, so the default first
 * frame is pixel-identical. Pure (no Android), unit-tested in ModelSpinTest.
 */
class ModelSpin {
    var rotX: Float = PolytopeMath.ROT_X0
    var rotY: Float = PolytopeMath.ROT_Y0
    var a4: Double = PolytopeMath.ROT_Y0.toDouble()
    var b4: Double = PolytopeMath.ROT_X0.toDouble()
    var velX: Float = 0f
    var velY: Float = 0f
    var dragging: Boolean = false

    /** A drag started: it takes over and kills any coast. */
    fun dragStart() { dragging = true; velX = 0f; velY = 0f }
    fun dragEnd() { dragging = false }

    /** A drag moved by (dx, dy) px: spin the 3D stage (same 0.01 rad/px as before). */
    fun dragBy(dx: Float, dy: Float) {
        val k = 0.01f
        velY = dx * k; velX = -dy * k
        rotY += velY; rotX += velX
    }

    val coasting: Boolean get() = velX != 0f || velY != 0f

    /**
     * Advance one frame of [dtMs] milliseconds. Returns true while another frame
     * is needed for MOTION (auto-rotating, dragging or coasting); false means the
     * model is still and the caller may stop its frame loop.
     */
    fun step(dtMs: Double, autoRotate: Boolean): Boolean {
        val dt = if (dtMs.isNaN() || dtMs < 0.0) 0.0 else minOf(dtMs, MAX_DT_MS)
        if (!dragging && coasting) {
            rotY += velY; rotX += velX
            velY *= 0.95f; velX *= 0.95f
            if (kotlin.math.abs(velY) < 1e-4f) velY = 0f
            if (kotlin.math.abs(velX) < 1e-4f) velX = 0f
        }
        if (autoRotate && !dragging) {
            val tau = 2.0 * Math.PI
            rotY = wrap(rotY + (dt / PolytopeMath.PERIOD_Y_MS * tau)).toFloat()
            rotX = wrap(rotX + (dt / PolytopeMath.PERIOD_X_MS * tau)).toFloat()
            a4 = wrap(a4 + dt / PolytopeMath.PERIOD_4A_MS * tau)
            b4 = wrap(b4 + dt / PolytopeMath.PERIOD_4B_MS * tau)
        }
        return autoRotate || dragging || coasting
    }

    /** Keep angles bounded (float precision over days of uptime); identical on screen. */
    private fun wrap(a: Double): Double {
        val tau = 2.0 * Math.PI
        return if (a > tau * 64 || a < -tau * 64) a % tau else a
    }

    companion object {
        /** A long frame gap (resume after the Home was hidden) never jumps the model. */
        const val MAX_DT_MS = 100.0
    }
}

package com.diegonmarcos.superapp.vision

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Where and how a piece of filter art is drawn: its pivot lands on ([anchorX], [anchorY]), [width] x [height] wide, turned [rotationDegrees] clockwise about that point. */
data class Placement(
    val anchorX: Float, val anchorY: Float, val width: Float, val height: Float,
    val rotationDegrees: Float, val pivotX: Float, val pivotY: Float,
)

/**
 * The filter anchor maths: a declared anchor (a landmark, an offset, a scale) plus a detected face or object box
 * becomes a [Placement]. Offsets and scales are in units of the face (its box width) or of the eyes (their distance), so one
 * declaration fits every face size; the offset is turned with the head, so a hat stays on a tilted head.
 * Pure, so it runs on the JVM.
 */
object FilterAnchors {
    val FACE_LANDMARKS = setOf("face_center", "eyes_center", "left_eye", "right_eye", "nose", "mouth_center", "chin", "head_top", "forehead")
    val OBJECT_LANDMARKS = setOf("center", "top_center", "bottom_center", "top_left", "top_right", "left_center", "right_center")
    val UNITS = setOf("face", "eyes")

    /** The two eyes ordered left-to-right IN THE IMAGE, whatever the detector calls them; null when either is missing. */
    private fun eyes(face: FaceInfo): Pair<Pt, Pt>? {
        val a = face.landmarks["left_eye"] ?: return null
        val b = face.landmarks["right_eye"] ?: return null
        return if (a.x <= b.x) a to b else b to a
    }

    /** The head's roll in degrees, clockwise on screen: the slope of the eye line, else the detector's euler Z (counter-clockwise positive, hence negated). */
    fun rollDegrees(face: FaceInfo): Float {
        val e = eyes(face) ?: return -face.eulerZ
        return Math.toDegrees(atan2((e.second.y - e.first.y).toDouble(), (e.second.x - e.first.x).toDouble())).toFloat()
    }

    fun eyeDistance(face: FaceInfo): Float {
        val e = eyes(face) ?: return face.box.width * 0.45f
        return hypot((e.second.x - e.first.x).toDouble(), (e.second.y - e.first.y).toDouble()).toFloat()
    }

    /** The length one offset / scale unit stands for. */
    fun unitLength(face: FaceInfo, unit: String): Float = if (unit == "eyes") eyeDistance(face) else face.box.width

    /** A named point of the face, in image pixels, turned with the head. */
    fun point(name: String, face: FaceInfo): Pt {
        val b = face.box
        val roll = Math.toRadians(rollDegrees(face).toDouble())
        val rx = cos(roll).toFloat(); val ry = sin(roll).toFloat()      // the head's right
        val dx = -ry; val dy = rx                                        // the head's down
        val cx = b.centerX; val cy = b.centerY
        fun at(base: Pt, right: Float, down: Float) = Pt(base.x + rx * right + dx * down, base.y + ry * right + dy * down)
        val centre = Pt(cx, cy)
        val e = eyes(face)
        val eyesCentre = e?.let { Pt((it.first.x + it.second.x) / 2f, (it.first.y + it.second.y) / 2f) } ?: at(centre, 0f, -0.12f * b.height)
        return when (name) {
            "face_center" -> centre
            "eyes_center" -> eyesCentre
            "left_eye" -> e?.first ?: at(centre, -0.2f * b.width, -0.12f * b.height)
            "right_eye" -> e?.second ?: at(centre, 0.2f * b.width, -0.12f * b.height)
            "nose" -> face.landmarks["nose"] ?: centre
            "mouth_center" -> {
                val l = face.landmarks["mouth_left"]; val r = face.landmarks["mouth_right"]
                if (l != null && r != null) Pt((l.x + r.x) / 2f, (l.y + r.y) / 2f) else face.landmarks["mouth_bottom"] ?: at(centre, 0f, 0.25f * b.height)
            }
            "chin" -> at(centre, 0f, b.height / 2f)
            "head_top" -> at(centre, 0f, -b.height / 2f)
            "forehead" -> at(eyesCentre, 0f, -0.5f * eyeDistance(face))
            else -> throw IllegalArgumentException("unknown face landmark '$name'")
        }
    }

    /** [anchor] applied to [face]; the art is [artW] x [artH] (only its aspect matters). */
    fun place(anchor: AnchorDecl, artW: Float, artH: Float, face: FaceInfo): Placement {
        val roll = rollDegrees(face)
        val rad = Math.toRadians(roll.toDouble())
        val rx = cos(rad).toFloat(); val ry = sin(rad).toFloat()
        val unit = unitLength(face, anchor.unit)
        val base = point(anchor.landmark, face)
        val ox = anchor.offsetX * unit; val oy = anchor.offsetY * unit
        val width = anchor.scale * unit
        return Placement(
            base.x + rx * ox + (-ry) * oy, base.y + ry * ox + rx * oy,
            width, width * artH / artW,
            (if (anchor.rotate) roll else 0f) + anchor.rotationDegrees, anchor.pivotX, anchor.pivotY,
        )
    }

    /** A named point of an object's [box]. */
    fun boxPoint(name: String, box: Box): Pt = when (name) {
        "center" -> Pt(box.centerX, box.centerY)
        "top_center" -> Pt(box.centerX, box.top)
        "bottom_center" -> Pt(box.centerX, box.bottom)
        "top_left" -> Pt(box.left, box.top)
        "top_right" -> Pt(box.right, box.top)
        "left_center" -> Pt(box.left, box.centerY)
        "right_center" -> Pt(box.right, box.centerY)
        else -> throw IllegalArgumentException("unknown object landmark '$name'")
    }

    /** [anchor] applied to a detected object's [box] (offset and scale in units of the box's width; no head to turn with). */
    fun placeOnBox(anchor: AnchorDecl, artW: Float, artH: Float, box: Box): Placement {
        val base = boxPoint(anchor.landmark, box)
        val width = anchor.scale * box.width
        return Placement(
            base.x + anchor.offsetX * box.width, base.y + anchor.offsetY * box.width,
            width, width * artH / artW, anchor.rotationDegrees, anchor.pivotX, anchor.pivotY,
        )
    }
}

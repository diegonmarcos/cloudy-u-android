package cld.camera

import com.diegonmarcos.superapp.vision.AnchorDecl
import com.diegonmarcos.superapp.vision.Box
import com.diegonmarcos.superapp.vision.FaceInfo
import com.diegonmarcos.superapp.vision.FilterAnchors
import com.diegonmarcos.superapp.vision.Pt
import com.diegonmarcos.superapp.vision.scaled
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** The filter anchor maths: scale and rotate from the face's landmarks. */
class FilterAnchorsTest {
    private val eps = 0.05f

    /** A level face 60 wide: eyes 20 apart at y=50, box 20..80 x 20..100. Labels as ML Kit gives them (left_eye = image left here). */
    private fun level(scale: Float = 1f, labelsSwapped: Boolean = false): FaceInfo {
        val l = Pt(40f * scale, 50f * scale); val r = Pt(60f * scale, 50f * scale)
        return FaceInfo(
            Box(20f * scale, 20f * scale, 80f * scale, 100f * scale), 0f, 0f, 0f,
            mapOf("left_eye" to (if (labelsSwapped) r else l), "right_eye" to (if (labelsSwapped) l else r), "nose" to Pt(50f * scale, 70f * scale)),
        )
    }

    private fun anchor(landmark: String, ox: Float = 0f, oy: Float = 0f, scale: Float = 1f, unit: String = "face", rotate: Boolean = true, extra: Float = 0f) =
        AnchorDecl(landmark, ox, oy, scale, unit, rotate, extra, 0.5f, 0.5f)

    @Test fun levelFaceHasNoRoll() {
        assertEquals(0f, FilterAnchors.rollDegrees(level()), eps)
        assertEquals(20f, FilterAnchors.eyeDistance(level()), eps)
    }

    @Test fun glassesSitOnTheEyesAndScaleWithTheEyeDistance() {
        val p = FilterAnchors.place(anchor("eyes_center", scale = 2.3f, unit = "eyes"), 100f, 40f, level())
        assertEquals(50f, p.anchorX, eps); assertEquals(50f, p.anchorY, eps)
        assertEquals(46f, p.width, eps)          // 2.3 x 20
        assertEquals(18.4f, p.height, eps)       // the art's 100:40 aspect
        assertEquals(0f, p.rotationDegrees, eps)
    }

    @Test fun placementScalesLinearlyWithTheFace() {
        val a = FilterAnchors.place(anchor("head_top", oy = 0.1f, scale = 0.8f), 100f, 100f, level())
        val b = FilterAnchors.place(anchor("head_top", oy = 0.1f, scale = 0.8f), 100f, 100f, level(scale = 2f))
        assertEquals(a.width * 2f, b.width, eps); assertEquals(a.height * 2f, b.height, eps)
        assertEquals(a.anchorX * 2f, b.anchorX, eps); assertEquals(a.anchorY * 2f, b.anchorY, eps)
    }

    @Test fun headTopIsTheTopOfTheBoxAndTheOffsetPushesDown() {
        val top = FilterAnchors.point("head_top", level())
        assertEquals(50f, top.x, eps); assertEquals(20f, top.y, eps)
        val p = FilterAnchors.place(anchor("head_top", oy = 0.5f), 100f, 100f, level())   // 0.5 face widths = 30 px down
        assertEquals(50f, p.anchorY, eps)
    }

    @Test fun tiltedHeadTurnsTheOverlayAndItsOffset() {
        // The eye line tilted by 30 degrees (clockwise on screen) about the eye centre (50,50), eyes still 20 apart.
        val a = Math.toRadians(30.0)
        val dx = (10 * cos(a)).toFloat(); val dy = (10 * sin(a)).toFloat()
        val face = FaceInfo(Box(20f, 20f, 80f, 100f), 0f, 0f, 0f,
            mapOf("left_eye" to Pt(50f - dx, 50f - dy), "right_eye" to Pt(50f + dx, 50f + dy)))
        assertEquals(30f, FilterAnchors.rollDegrees(face), eps)
        val p = FilterAnchors.place(anchor("eyes_center", ox = 1f, scale = 1f, unit = "eyes"), 100f, 100f, face)
        assertEquals(30f, p.rotationDegrees, eps)
        // One eye distance (20) along the head's right: (50 + 20 cos30, 50 + 20 sin30).
        assertEquals(50f + 20f * cos(a).toFloat(), p.anchorX, eps)
        assertEquals(50f + 20f * sin(a).toFloat(), p.anchorY, eps)
        // "Up" the head (offset y = -1 eye distance) follows the tilt too: (50 + 20 sin30, 50 - 20 cos30).
        val up = FilterAnchors.place(anchor("eyes_center", oy = -1f, unit = "eyes"), 100f, 100f, face)
        assertEquals(50f + 20f * sin(a).toFloat(), up.anchorX, eps)
        assertEquals(50f - 20f * cos(a).toFloat(), up.anchorY, eps)
    }

    @Test fun rotateFalseKeepsTheArtUprightAndExtraRotationAdds() {
        val a = Math.toRadians(20.0)
        val face = FaceInfo(Box(20f, 20f, 80f, 100f), 0f, 0f, 0f,
            mapOf("left_eye" to Pt(50f - (10 * cos(a)).toFloat(), 50f - (10 * sin(a)).toFloat()), "right_eye" to Pt(50f + (10 * cos(a)).toFloat(), 50f + (10 * sin(a)).toFloat())))
        assertEquals(0f, FilterAnchors.place(anchor("eyes_center", rotate = false), 10f, 10f, face).rotationDegrees, eps)
        assertEquals(25f, FilterAnchors.place(anchor("eyes_center", rotate = false, extra = 25f), 10f, 10f, face).rotationDegrees, eps)
        assertEquals(45f, FilterAnchors.place(anchor("eyes_center", extra = 25f), 10f, 10f, face).rotationDegrees, eps)
    }

    @Test fun rollIsTheSameWhateverTheDetectorCallsLeftAndRight() {
        val a = FilterAnchors.place(anchor("eyes_center", ox = 0.5f, unit = "eyes"), 10f, 10f, level())
        val b = FilterAnchors.place(anchor("eyes_center", ox = 0.5f, unit = "eyes"), 10f, 10f, level(labelsSwapped = true))
        assertEquals(a.anchorX, b.anchorX, eps); assertEquals(a.rotationDegrees, b.rotationDegrees, eps)
        assertEquals(0f, b.rotationDegrees, eps)
    }

    @Test fun withoutEyesTheRollFallsBackToTheDetectorsEulerZ() {
        val face = FaceInfo(Box(20f, 20f, 80f, 100f), eulerZ = 12f)
        assertEquals(-12f, FilterAnchors.rollDegrees(face), eps)             // ML Kit: counter-clockwise positive
        assertEquals(27f, FilterAnchors.eyeDistance(face), eps)              // 0.45 x the box width
        val c = FilterAnchors.point("face_center", face)
        assertEquals(50f, c.x, eps); assertEquals(60f, c.y, eps)
    }

    @Test fun objectStickersFollowTheBox() {
        val box = Box(100f, 200f, 300f, 400f)                                 // 200 wide
        val p = FilterAnchors.placeOnBox(AnchorDecl("top_center", 0f, -0.1f, 0.3f, "face", false, 0f, 0.5f, 1f), 100f, 50f, box)
        assertEquals(200f, p.anchorX, eps); assertEquals(180f, p.anchorY, eps)   // top edge, 0.1 widths up
        assertEquals(60f, p.width, eps); assertEquals(30f, p.height, eps)
        val q = FilterAnchors.placeOnBox(AnchorDecl("top_right", 0f, 0f, 0.5f, "face", false, 0f, 0.5f, 0.5f), 10f, 10f, Box(0f, 0f, 400f, 100f))
        assertEquals(400f, q.anchorX, eps); assertEquals(0f, q.anchorY, eps); assertEquals(200f, q.width, eps)
    }

    @Test fun aFaceFoundOnAShrunkCopyScalesBackToTheFullFrame() {
        val small = level()
        val big = small.scaled(2f)
        val ref = level(scale = 2f)
        assertEquals(ref.box, big.box)
        assertEquals(ref.landmarks, big.landmarks)
        // The same anchor on both gives the same placement up to the scale, so a filter lands identically on a 480 px copy and the full still.
        val a = FilterAnchors.place(anchor("eyes_center", scale = 2.3f, unit = "eyes"), 100f, 40f, small)
        val b = FilterAnchors.place(anchor("eyes_center", scale = 2.3f, unit = "eyes"), 100f, 40f, big)
        assertEquals(a.anchorX * 2f, b.anchorX, eps); assertEquals(a.width * 2f, b.width, eps)
    }
}

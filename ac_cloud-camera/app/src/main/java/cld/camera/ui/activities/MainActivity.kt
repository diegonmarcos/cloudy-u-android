package cld.camera.ui.activities

import android.Manifest
import android.animation.Animator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.text.util.Linkify
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ScaleGestureDetector.OnScaleGestureListener
import android.view.Surface
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.view.PreviewView
import androidx.camera.view.PreviewView.StreamState
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMargins
import cld.camera.App
import cld.camera.CamConfig
import cld.camera.CameraMode
import cld.camera.ITEM_TYPE_IMAGE
import cld.camera.ITEM_TYPE_VIDEO
import cld.camera.BuildConfig
import cld.camera.R
import cld.camera.analyzer.ScannedPayload
import cld.camera.capturer.ImageCapturer
import cld.camera.capturer.VideoCapturer
import cld.camera.capturer.getVideoThumbnail
import cld.camera.shareCapturedItem
import cld.camera.databinding.ActivityMainBinding
import cld.camera.databinding.ScanResultDialogBinding
import cld.camera.ktx.SystemSettingsObserver
import cld.camera.ktx.applyPreviewRatio
import cld.camera.notifier.SensorOrientationChangeNotifier
import cld.camera.util.CloudGallery
import cld.camera.util.isPackageInstalled
import cld.camera.objects.ObjectsController
import cld.camera.ui.ModeNav
import cld.camera.vr.VrController
import cld.camera.ui.CountDownTimerUI
import cld.camera.ui.CustomGrid
import cld.camera.ui.QROverlay
import cld.camera.ui.QRToggle
import cld.camera.ui.SettingsDialog
import cld.camera.ui.seekbar.ExposureBar
import cld.camera.ui.seekbar.ZoomBar
import cld.camera.ui.showIgnoringShortEdgeMode
import cld.camera.util.CameraControl
import cld.camera.util.ImageResizer
import cld.camera.util.executeIfAlive
import cld.camera.util.resolveActivity
import cld.camera.util.setBlurBitmapCompat
import cld.camera.voice.VoiceShutterController
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.snackbar.Snackbar
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.google.zxing.BarcodeFormat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

open class MainActivity : AppCompatActivity(),
    OnTouchListener,
    OnScaleGestureListener,
    GestureDetector.OnGestureListener,
    GestureDetector.OnDoubleTapListener,
    SensorOrientationChangeNotifier.Listener {

    private val application: App
        get() = applicationContext as App

    private lateinit var binding: ActivityMainBinding

    private val cameraPermission = arrayOf(Manifest.permission.CAMERA)

    lateinit var previewView: PreviewView
    lateinit var previewContainer: ConstraintLayout
    lateinit var bottomOverlay: View

    // Hold a reference to the manual permission dialog to avoid re-creating it if it
    // is already visible and to dismiss it if the permission gets granted.
    private var cameraPermissionDialog: AlertDialog? = null
    private var audioPermissionDialog: AlertDialog? = null
    var lastFrame: Bitmap? = null
        private set

    private lateinit var mainFrame: View
    lateinit var rootView: View

    lateinit var qrScanToggles: View
    private lateinit var moreOptionsToggle: View

    lateinit var qrToggle: QRToggle
    lateinit var dmToggle: QRToggle
    lateinit var cBToggle: QRToggle
    lateinit var azToggle: QRToggle

    lateinit var imageCapturer: ImageCapturer
    lateinit var voiceShutter: VoiceShutterController
    lateinit var videoCapturer: VideoCapturer

    lateinit var flipCameraCircle: View
    lateinit var cancelButtonView: ImageView
    lateinit var modeNav: ModeNav

    private var objectsController: ObjectsController? = null

    /** #1533 the Objects page, wired to its views the first time it is needed. */
    val objects: ObjectsController
        get() = objectsController ?: ObjectsController(
            this, binding.objectsOverlay, binding.objectsPanel, binding.objectsStatus,
            binding.objectsPause, binding.objectsThreshold, binding.objectsThresholdValue,
        ).also { objectsController = it }

    val objectsIfStarted: ObjectsController? get() = objectsController

    private var vrController: VrController? = null

    /** #1533 the VR page, wired to its views the first time it is needed. */
    val vr: VrController
        get() = vrController ?: VrController(
            this, binding.vrView, binding.vrPanel, binding.vrStatus, binding.vrFilters, binding.vrRecord,
        ).also { vrController = it }

    val vrIfStarted: VrController? get() = vrController
    lateinit var thirdCircle: ImageView
    lateinit var openCloudGalleryVideo: ImageButton
    lateinit var openCloudGalleryPhoto: ImageButton
    lateinit var captureButton: ImageButton

    private lateinit var scaleGestureDetector: ScaleGestureDetector
    lateinit var timerView: TextView
    lateinit var thirdOption: View
    lateinit var imagePreview: ShapeableImageView
    lateinit var previewLoader: ProgressBar
    private var isZooming = false

    lateinit var zoomBar: ZoomBar
    lateinit var zoomBarPanel: LinearLayout

    lateinit var exposureBar: ExposureBar
    lateinit var exposureBarPanel: LinearLayout

    lateinit var qrOverlay: QROverlay

    lateinit var threeButtons: LinearLayout

    lateinit var settingsIcon: ImageView

    private lateinit var exposurePlusIcon: ImageView
    private lateinit var exposureNegIcon: ImageView

    private lateinit var zoomInIcon: ImageView
    private lateinit var zoomOutIcon: ImageView

    lateinit var flipCamIcon: ImageView

    lateinit var mainOverlay: ImageView

    lateinit var settingsDialog: SettingsDialog
    lateinit var previewGrid: CustomGrid

    private var wasSwiping = false

    lateinit var cdTimer: CountDownTimerUI
    var timerDuration = 0

    lateinit var cbText: TextView
    lateinit var cbCross: ImageView

    lateinit var gCircleFrame: FrameLayout

    private lateinit var gAngleTextView: TextView
    private lateinit var gCircle: LinearLayout

    private lateinit var gLineX: View
    private lateinit var gLineZ: View

    private lateinit var gLeftDash: View
    private lateinit var gRightDash: View

    lateinit var muteToggle: ShapeableImageView

    private var bottomNavigationBarPadding: Int = 0

    private var shouldGyroVibrate = true
    private var hasGyroVibrated = false

    private val gyroVibRunnable = Runnable {
        vibrateDevice()
        hasGyroVibrated = true
    }

    val thumbnailLoaderExecutor = Executors.newSingleThreadExecutor()

    private val runnable = Runnable {
        val factory: MeteringPointFactory = SurfaceOrientedMeteringPointFactory(
            previewView.width.toFloat(), previewView.height.toFloat()
        )

        val autoFocusPoint = factory.createPoint(
            previewView.width / 2.0f,
            previewView.height / 2.0f, QROverlay.RATIO
        )

        camConfig.camera?.cameraControl?.startFocusAndMetering(
            FocusMeteringAction.Builder(autoFocusPoint).disableAutoCancel().build()
        )

        startFocusTimer()
    }

    private val handler = Handler(Looper.getMainLooper())

    // #461a: after a quiet interval with no decode, the scanner says so instead
    // of holding a silent empty box. Cleared the moment a payload arrives.
    private val noBarcodeCallback: Runnable = Runnable {
        if (camConfig.isQRMode && !isQRDialogShowing) {
            showMessage(R.string.no_barcode_in_frame)
        }
    }

    fun scheduleNoBarcodeHint() {
        handler.removeCallbacks(noBarcodeCallback)
        handler.postDelayed(noBarcodeCallback, NO_BARCODE_HINT_DELAY_MS)
    }

    fun clearNoBarcodeHint() {
        handler.removeCallbacks(noBarcodeCallback)
    }

    private lateinit var snackBar: Snackbar

    private lateinit var focusRing: ImageView

    private val focusRingHandler: Handler = Handler(Looper.getMainLooper())
    private val focusRingCallback: Runnable = Runnable {
        focusRing.visibility = View.INVISIBLE
    }

    lateinit var micOffIcon: ImageView

    private var shouldRestartRecording = false

    fun startFocusTimer() {
        handler.postDelayed(runnable, autoCenterFocusDuration)
    }

    fun cancelFocusTimer() {
        handler.removeCallbacks(runnable)
    }

    /**
     * #1515 every grant of this screen (camera, microphone, location) is asked in Configs > Perms (libs:perms): a missing
     * one opens the page on its rows ONCE per screen; [onResume] re-checks it when the user comes back ([checkPermissions],
     * [resumeAfterPerms]). Never throws.
     */
    private var askedPerms = emptySet<String>()
    private var pendingMicRestart = false

    private fun openPerms(feature: String, ids: List<String>): Boolean {
        if (askedPerms.containsAll(ids)) return false
        askedPerms = askedPerms + ids
        runCatching { com.diegonmarcos.superapp.perms.PermsLink.open(this, feature, ids) }
        return true
    }

    /** #1515 "denied once, Android would ask again" for every one of [ids], read by libs:perms (Perms.status), the same
     *  state Configs > Perms shows. False when unknown. */
    private fun deniedOnce(vararg ids: String): Boolean = ids.all { id ->
        runCatching {
            val e = com.diegonmarcos.superapp.perms.Perms.catalogue(this).entries.firstOrNull { it.id == id } ?: return@runCatching false
            val st = com.diegonmarcos.superapp.perms.Perms.status(this, e, this)
            st.state == com.diegonmarcos.superapp.perms.PermState.DENIED && st.reason.contains("denied once")
        }.getOrDefault(false)
    }

    /** Back from Configs > Perms: the recording that waited for the microphone restarts, or keeps going without audio. */
    private fun resumeAfterPerms() {
        if (!pendingMicRestart) return
        pendingMicRestart = false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            shouldRestartRecording = true
            camConfig.startCamera(true)
        } else {
            showAudioPermissionDeniedDialog { videoCapturer.startRecording() }
        }
    }

    // Used to request permission from the user
    var dirPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult())
    { result ->

        val data: Uri? = result.data?.data

        if (data?.encodedPath != null) {
            val file = File(data.encodedPath!!)
            if (file.exists()) {
                showMessage(getString(R.string.file_already_exists, file.absolutePath))
            } else {
                showMessage(getString(R.string.file_does_not_exist, data.encodedPath))
            }
        }

        Log.i(TAG, "Selected location: ${data?.encodedPath!!}")
    }

    private fun showAudioPermissionDeniedDialog(onDisableAudio: () -> Unit = {}) {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.audio_permission_dialog_title)
            .setMessage(R.string.audio_permission_dialog_message)

        // #1515 the microphone grant lives in Configs > Perms: open it on that row
        builder.setPositiveButton(R.string.settings) { _: DialogInterface?, _: Int ->
            runCatching { com.diegonmarcos.superapp.perms.PermsLink.open(this, "Camera: video audio", listOf("microphone")) }
        }
        builder.setNegativeButton(R.string.cancel, null)

        builder.setNeutralButton(R.string.disable_audio) { _: DialogInterface?, _: Int ->
            camConfig.includeAudio = false
            onDisableAudio()
        }

        audioPermissionDialog = builder.showIgnoringShortEdgeMode()
    }

    fun updateLastFrame() {
        lastFrame = previewView.bitmap
    }

    private fun animateFocusRing(x: Float, y: Float) {

        // Move the focus ring so that its center is at the tap location (x, y)
        val width = focusRing.width.toFloat()
        focusRing.updateLayoutParams<ConstraintLayout.LayoutParams> {
            updateMargins(
                left = (x - width / 2).roundToInt(),
                top = (y - width / 2).roundToInt()
            )
        }

        // Show focus ring
        focusRing.visibility = View.VISIBLE
        focusRing.alpha = 1f

        if (areSystemAnimationsEnabled()) {
            // Animate the focus ring to disappear
            focusRing.animate()
                .setStartDelay(500)
                .setDuration(300)
                .alpha(0f)
                .setListener(object : Animator.AnimatorListener {

                    var isCancelled = false

                    override fun onAnimationStart(animation: Animator) {}

                    override fun onAnimationEnd(animator: Animator) {

                        if (!isCancelled) {
                            focusRing.visibility = View.INVISIBLE
                        }

                        isCancelled = false
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        isCancelled = true
                    }

                    override fun onAnimationRepeat(animation: Animator) {}
                }).start()
        } else {
            focusRingHandler.removeCallbacks(focusRingCallback)
            focusRingHandler.postDelayed(focusRingCallback, 800)
        }
    }

    private fun areSystemAnimationsEnabled(): Boolean {

        val duration: Float = Settings.Global.getFloat(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        )

        val transition: Float = Settings.Global.getFloat(
            contentResolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE, 1f
        )

        return duration != 0f && transition != 0f
    }

    /**
     * Opens the folder this camera saves into, in Cloud Gallery.
     *
     * Four outcomes, all of them distinguishable, and none of them a guess.
     *
     * The one this exists to kill: resolveActivity() returning null used to be reported
     * as "Cloud Gallery is not installed", and that sentence was wrong every time
     * the app WAS installed and merely predated the intent-filter it publishes now. It
     * sent the owner looking for a missing app that was on his home screen, while the
     * actual fix - install the newer artefact - was never named. resolveActivity() and
     * "installed" are two different questions, so this asks both.
     *
     * It must never quietly land on Gallery's home screen either: that looks enough
     * like success to hide a broken folder lookup, so there is no generic-VIEW fallback.
     */
    private fun openCaptureFolderInCloudGallery() {
        val relativePath = CloudGallery.captureFolderRelativePath(camConfig.storageLocation)
        if (relativePath == null) {
            Log.i(TAG, "capture folder is not addressable by MediaStore path")
            showMessage(R.string.cloud_gallery_folder_unavailable)
            return
        }

        val intent = CloudGallery.viewFolderIntent(relativePath)

        // The manifest declares a <queries> entry for the package, without which BOTH
        // this and isPackageInstalled() below answer "absent" on targetSdk 30+ for an
        // app that is installed.
        if (packageManager.resolveActivity(intent, 0L) == null) {
            val installed = packageManager.isPackageInstalled(CloudGallery.PACKAGE)
            Log.i(
                TAG,
                "Gallery did not resolve $relativePath (package installed: $installed)"
            )
            showCloudGalleryRecovery(
                if (installed) R.string.cloud_gallery_outdated
                else R.string.cloud_gallery_not_installed,
                if (installed) R.string.update else R.string.install
            )
            return
        }

        try {
            startActivity(intent)
            Log.i(TAG, "Opening Gallery on $relativePath")
        } catch (e: Exception) {
            // It resolved a moment ago, so this is not absence - a disabled component, a
            // profile split, a dead activity. Saying "not installed" here was the same
            // lie by a second route.
            Log.e(TAG, "unable to open Gallery", e)
            showMessage(R.string.cloud_gallery_open_failed)
        }
    }

    /**
     * Says what is wrong, and offers Constellation - the fleet installer - as the way out.
     *
     * The action is offered only when Constellation itself resolves. A snackbar button
     * that throws ActivityNotFoundException would be one more thing lying about what is
     * on the phone.
     */
    private fun showCloudGalleryRecovery(@StringRes message: Int, @StringRes action: Int) {
        val installer = packageManager
            .getLaunchIntentForPackage(CloudGallery.CONSTELLATION_PACKAGE)

        if (installer == null) {
            showMessage(message)
            return
        }

        showMessage(message, getString(action)) {
            startActivity(installer)
        }
    }

    private fun openGallery() {
        check(this !is CaptureActivity)

        Intent(this, InAppGallery::class.java).let {
            if (this is SecureMainActivity) {
                it.putExtra(InAppGallery.INTENT_KEY_SECURE_MODE, true)

                val list = capturedItems
                if (list.isEmpty()) {
                    showMessage(R.string.no_image)
                    return
                }
                it.putParcelableArrayListExtra(InAppGallery.INTENT_KEY_LIST_OF_SECURE_MODE_CAPTURED_ITEMS, list)
            } else {
                it.putExtra(InAppGallery.INTENT_KEY_VIDEO_ONLY_MODE, requiresVideoModeOnly)
            }

            if (isThumbnailLoaded) { // indicates that last captured item is accessible
                it.putExtra(InAppGallery.INTENT_KEY_LAST_CAPTURED_ITEM, camConfig.lastCapturedItem)
            }

            startActivity(it)
        }

    }

    private fun checkPermissions() {
        Log.i(TAG, "Checking camera status...")

        // Check if the app has access to the user's camera
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) ==
                    PackageManager.PERMISSION_GRANTED -> {

                // If the user has manually granted the permission, dismiss the dialog.
                if (cameraPermissionDialog != null && cameraPermissionDialog!!.isShowing) cameraPermissionDialog!!.cancel()
                Log.i(TAG, "Permission granted.")

                // Setup the camera since the permission is available
                camConfig.initializeCamera()
            }
            deniedOnce("camera") -> {

                Log.i(TAG, "The user has default denied camera permission.")

                // Don't build and show a new dialog if it's already visible
                if (cameraPermissionDialog != null && cameraPermissionDialog!!.isShowing) return
                val builder = MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.camera_permission_dialog_title)
                    .setMessage(R.string.camera_permission_dialog_message)
                val positiveClicked = AtomicBoolean(false)

                // Open the settings menu for the current app
                // #1515 the camera grant lives in Configs > Perms: open it on that row
                builder.setPositiveButton(R.string.settings) { _: DialogInterface?, _: Int ->
                    positiveClicked.set(true)
                    runCatching { com.diegonmarcos.superapp.perms.PermsLink.open(this, "Camera", listOf("camera")) }
                }
                builder.setNegativeButton(R.string.cancel, null)
                builder.setOnDismissListener {

                    // The dialog could have either been dismissed by clicking on the
                    // background or by clicking the cancel button. So in those cases,
                    // the app should exit as the app depends on the camera permission.
                    if (!positiveClicked.get()) {
                        finish()
                    }
                }
                cameraPermissionDialog = builder.showIgnoringShortEdgeMode()
            }

            // Request for the permission (Android will actually popup the permission
            // dialog in this case)
            else -> {
                Log.i(TAG, "Requesting permission from user...")

                // #1515 asked in Configs > Perms (once per screen); back here without it, the screen closes as it
                // always did when the camera grant is refused (the app cannot work without it).
                if (!openPerms("Camera", listOf("camera"))) {
                    Log.i(TAG, "Permission denied for camera.")
                    finish()
                }
            }
        }

        audioPermissionDialog?.let { dialog ->
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO
                ) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                if (dialog.isShowing) {
                    dialog.dismiss()
                }
            }
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // there are no camera controls in qr mode
        if (camConfig.isQRMode) {
            return super.onKeyUp(keyCode, event)
        }

        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_CAMERA -> {
                captureButton.performClick()
            }
            KeyEvent.KEYCODE_FOCUS -> {
                // cancel any manual focus
                // CameraX will start the continuous autofocus (if supported) automatically
                previewView.controller?.cameraControl?.cancelFocusAndMetering()
            }
            KeyEvent.KEYCODE_ZOOM_IN -> {
                cameraControl.zoomIn()
            }
            KeyEvent.KEYCODE_ZOOM_OUT -> {
                cameraControl.zoomOut()
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            // Pretend as if the event was handled by the app (avoid volume bar from appearing)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        resumeOrientationSensor()
        // Check camera permission again if the user switches back to the app (maybe
        // after enabling/disabling the camera permission in Settings)
        // Will also be called by Android Lifecycle when the app starts up
        checkPermissions()
        resumeAfterPerms()

        if (camConfig.isQRMode) {
            startFocusTimer()
        }

        if (this !is SecureActivity) {
            camConfig.fetchLastCapturedItemFromSharedPrefs()
        }

        updateThumbnail()

        if (camConfig.requireLocation) {
            requestLocation()
        }

        // If the preview of video capture activity isn't showing
        if (!(this is VideoCaptureActivity && thirdOption.visibility == View.VISIBLE)) {
            if (!isQRDialogShowing) {
                camConfig.initializeCamera(true)
            }
        }
    }

    val requiresVideoModeOnly: Boolean
        get() {
            return this is VideoOnlyActivity || this is VideoCaptureActivity
        }

    override fun onPause() {
        super.onPause()
        pauseOrientationSensor()
        // The countdown would otherwise keep ticking while the app is in the background and fire a
        // capture into a camera that has already been unbound.
        cdTimer.cancelTimer()
        // #1533 battery: an analysis page stops analysing the moment the activity leaves the screen.
        objectsIfStarted?.onActivityPaused()
        vrIfStarted?.onActivityPaused()
        if (camConfig.isQRMode) {
            cancelFocusTimer()
        } else {
            imageCapturer.cancelPendingCaptureRequest()
        }
        if (camConfig.requireLocation) {
            application.dropLocationUpdates()
        }
        lastFrame = null
    }

    lateinit var gestureDetector: GestureDetector

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        snackBar = Snackbar.make(binding.root, "", Snackbar.LENGTH_LONG)

        gestureDetector = GestureDetector(this, this)

        camConfig = CamConfig(this)
        cameraControl = CameraControl(camConfig)
        mainOverlay = binding.mainOverlay
        imageCapturer = ImageCapturer(this)
        videoCapturer = VideoCapturer(this)
        // #461a: the voice shutter. Declared trigger word + off-by-default live
        // in build.json; this wires it to the real shutter and to honest
        // failure messages. Deliberately invisible until the settings toggle
        // opts in — a camera that listens by default is not acceptable.
        voiceShutter = VoiceShutterController(
            this,
            onMessage = { msg -> showMessage(msg) },
            onShutter = {
                if (camConfig.canTakePicture && !videoCapturer.isRecording) {
                    imageCapturer.takePicture()
                }
            },
        )
        thirdOption = binding.thirdOption
        previewLoader = binding.previewLoading
        imagePreview = binding.imagePreview
        previewView = binding.preview
        previewView.scaleType = PreviewView.ScaleType.FIT_START
        previewContainer = binding.previewContainer
        bottomOverlay = binding.bottomOverlay
        scaleGestureDetector = ScaleGestureDetector(this, this)

        // #868 the mode switcher: libs:bottomnav's island + page strip, declared in build.json::ui.
        modeNav = ModeNav(
            binding.cameraModeTabs, binding.cameraModeIsland, binding.cameraModeStrip,
            NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION),
        ) { mode -> finalizeMode(mode) }

        timerView = binding.timer
        previewView.previewStreamState.observe(this) { state: StreamState ->
            if (state == StreamState.STREAMING) {
                mainOverlay.visibility = View.INVISIBLE
                camConfig.reloadSettings()
                if (!camConfig.isQRMode) {
                    previewGrid.visibility = View.VISIBLE
                    if (!settingsDialog.isShowing) {
                        settingsIcon.visibility = View.VISIBLE
                    }
                    settingsIcon.isEnabled = true
                }

                restartRecordingIfPermissionsWasUnavailable()
            } else {
                previewGrid.visibility = View.INVISIBLE
                val lastFrame = lastFrame
                if (lastFrame != null && this !is CaptureActivity) {
                    setBlurBitmapCompat(mainOverlay, lastFrame)
                    settingsIcon.visibility = View.INVISIBLE
                    settingsIcon.isEnabled = false
                    mainOverlay.visibility = View.VISIBLE
                }
            }
        }
        flipCameraCircle = binding.flipCameraCircle

        flipCamIcon = binding.flipCameraIconContent

        var tapDownTimestamp: Long = 0
        flipCameraCircle.setOnTouchListener { _, event ->
            when (event?.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (tapDownTimestamp == 0L) {
                        tapDownTimestamp = System.currentTimeMillis()
                        flipCameraCircle.animate().scaleXBy(0.05f).setDuration(300).start()
                        flipCameraCircle.animate().scaleYBy(0.05f).setDuration(300).start()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dif = System.currentTimeMillis() - tapDownTimestamp
                    if (dif < 300) {
                        flipCameraCircle.performClick()
                    }

                    tapDownTimestamp = 0
                    flipCameraCircle.animate().cancel()
                    flipCameraCircle.animate().scaleX(1f).setDuration(300).start()
                    flipCameraCircle.animate().scaleY(1f).setDuration(300).start()
                }
                else -> {
                }
            }
            true
        }
        flipCameraCircle.setOnClickListener {
            resetAutoSleep()
            if (camConfig.isQRMode) {
                camConfig.scanAllCodes = !camConfig.scanAllCodes
                return@setOnClickListener
            }

            if (videoCapturer.isRecording) {
                videoCapturer.isPaused = !videoCapturer.isPaused
                return@setOnClickListener
            }

            val flipCameraIcon: ImageView = binding.flipCameraIcon
            val rotation: Float = if (flipCameraIcon.rotation < 180) {
                180f
            } else {
                360f
            }

            val rotate = RotateAnimation(
                0F,
                rotation,
                Animation.RELATIVE_TO_SELF,
                0.5f,
                Animation.RELATIVE_TO_SELF,
                0.5f
            )
            rotate.duration = 400
            rotate.interpolator = LinearInterpolator()

            it.startAnimation(rotate)
            camConfig.toggleCameraSelector()
        }
        thirdCircle = binding.thirdCircle
        thirdCircle.setOnClickListener {
            resetAutoSleep()
            if (videoCapturer.isRecording) {
                imageCapturer.takePicture()
            } else {
                openGallery()
                Log.i(TAG, "Attempting to open gallery...")
            }
        }

        thirdCircle.setOnLongClickListener {
            if (videoCapturer.isRecording) {
                imageCapturer.takePicture()
            } else {
                shareLatestMedia()

            }

            return@setOnLongClickListener true
        }

        // Both buttons open the same folder on purpose: this camera writes photos and
        // videos into one capture folder, so two destinations would need the folder split
        // first. The pair exists now so that split is a change of target, not of layout.
        openCloudGalleryVideo = binding.openCloudGalleryVideo
        openCloudGalleryPhoto = binding.openCloudGalleryPhoto
        for (button in listOf(openCloudGalleryVideo, openCloudGalleryPhoto)) {
            button.setOnClickListener {
                resetAutoSleep()
                openCaptureFolderInCloudGallery()
            }
        }

        captureButton = binding.captureButton
        captureButton.setOnClickListener {
            resetAutoSleep()
            if (camConfig.isVideoMode) {
                if (videoCapturer.isRecording) {
                    videoCapturer.stopRecording()
                } else {
                    videoCapturer.startRecording()
                }
            } else if (camConfig.isObjectsMode) {
                objects.snapshot()
            } else if (camConfig.isVrMode) {
                vr.snapshot()
            } else if (camConfig.isQRMode) {
                camConfig.toggleTorchState()
                if (camConfig.isTorchOn) {
                    setCaptureButtonIcon(R.drawable.torch_on_button, R.string.turn_torch_off)
                } else {
                    setCaptureButtonIcon(R.drawable.torch_off_button, R.string.turn_torch_on)
                }
            } else {
                if (timerDuration == 0) {
                    imageCapturer.takePicture()
                } else {
                    if (cdTimer.isRunning) {
                        cdTimer.cancelTimer()
                    } else {
                        cdTimer.startTimer()
                    }
                }
            }
        }

        cancelButtonView = binding.cancelButton

        zoomBar = binding.zoomBar
        zoomBar.setMainActivity(this)

        zoomBarPanel = binding.zoomBarPanel

        exposureBar = binding.exposureBar
        exposureBar.setMainActivity(this)

        exposureBarPanel = binding.exposureBarPanel

        qrOverlay = binding.qrOverlay

        threeButtons = binding.threeButtons
        settingsIcon = binding.settingsOption
        settingsIcon.setOnClickListener {
            if (!camConfig.isQRMode)
                settingsDialog.show()
        }

        settingsIcon.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    rootView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val displayCutout = window.decorView.rootWindowInsets.displayCutout
                    val layoutParams = (settingsIcon.layoutParams as RelativeLayout.LayoutParams)

                    val rect = if (displayCutout?.boundingRects?.isNotEmpty() == true)
                        displayCutout.boundingRects.first() else null

                    val windowsSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        windowManager.currentWindowMetrics.bounds
                    } else {
                        val size = Point()
                        // defaultDisplay isn't deprecated below API 30 as highlighted by the IDE
                        // and this code would only execute if it is (Hint: enclosing if-block)
                        @Suppress("DEPRECATION")
                        windowManager.defaultDisplay.getRealSize(size)
                        Rect(0, 0, size.x, size.y)
                    }

                    if (rect == null || rect.left <= 0 || rect.right == windowsSize.right) {
                        layoutParams.addRule(RelativeLayout.CENTER_HORIZONTAL)
                    } else {
                        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                    }
                }
            })

        exposurePlusIcon = binding.exposurePlusIcon
        exposureNegIcon = binding.exposureNegIcon

        zoomInIcon = binding.zoomInIcon
        zoomOutIcon = binding.zoomOutIcon

        previewGrid = binding.previewGrid
        previewGrid.setMainActivity(this)

        rootView = binding.root

        mainFrame = binding.mainFrame

        qrScanToggles = binding.qrScanToggles

        var isInsetSet = false

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())

            view.layoutParams = (view.layoutParams as ViewGroup.MarginLayoutParams).let {
                it.setMargins(
                    insets.left,
                    0,
                    insets.right,
                    0,
                )

                it
            }

            zoomBarPanel.setPadding(0, 0, 0, insets.bottom)
            exposureBarPanel.setPadding(0, 0, 0, insets.bottom)
            bottomNavigationBarPadding = insets.bottom
            // The mode nav is the bottom-most thing on screen: only the navigation-bar inset lifts it,
            // and the preview (main_frame) is laid out above its top edge.
            modeNav.view.layoutParams = (modeNav.view.layoutParams as ViewGroup.MarginLayoutParams).also {
                it.bottomMargin = insets.bottom
            }

            if (insets.top != 0 && !isInsetSet) {
                mainFrame.layoutParams =
                    (mainFrame.layoutParams as ViewGroup.MarginLayoutParams).let {
                        it.setMargins(
                            it.leftMargin,
                            (8 * resources.displayMetrics.density.toInt()) + insets.top,
                            it.rightMargin,
                            it.bottomMargin,
                        )

                        it
                    }

                qrScanToggles.layoutParams =
                    (qrScanToggles.layoutParams as ViewGroup.MarginLayoutParams).let {
                        it.setMargins(
                            it.leftMargin,
                            (16 * resources.displayMetrics.density.toInt()) +
                                    insets.top,
                            it.rightMargin,
                            it.bottomMargin,
                        )

                        it
                    }

                isInsetSet = true
            }

            WindowInsetsCompat.CONSUMED
        }

        setContentView(binding.getRoot())

        WindowInsetsControllerCompat(window, rootView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        FleetChrome.apply(this)

        cdTimer = binding.cTimer
        cdTimer.setMainActivity(this)

        cbText = binding.captureButtonText
        cbCross = binding.captureButtonCross

        val themedContext = DynamicColors.wrapContextIfAvailable(this, R.style.Theme_SettingsDialog)
        settingsDialog = SettingsDialog(this, themedContext)

        SystemSettingsObserver(lifecycle,Settings.System.ACCELEROMETER_ROTATION,this) {
            forceUpdateOrientationSensor()
        }

        focusRing = binding.focusRing

        micOffIcon = binding.micOff

        previewView.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    previewView.viewTreeObserver.removeOnPreDrawListener(this)
                    repositionTabLayout()
                    return true
                }
            }
        )

        moreOptionsToggle = binding.moreOptions
        moreOptionsToggle.setOnClickListener {
            camConfig.showMoreOptionsForQR()
        }

        qrToggle = binding.qrScanToggle
        qrToggle.mActivity = this
        qrToggle.key = BarcodeFormat.QR_CODE.name

        dmToggle = binding.dataMatrixToggle
        dmToggle.mActivity = this
        dmToggle.key = BarcodeFormat.DATA_MATRIX.name

        cBToggle = binding.pdf417Toggle
        cBToggle.mActivity = this
        cBToggle.key = BarcodeFormat.PDF_417.name

        azToggle = binding.aztecToggle
        azToggle.mActivity = this
        azToggle.key = BarcodeFormat.AZTEC.name

        camConfig.loadSettings()

        gCircle = binding.gCircle
        gAngleTextView = binding.gCircleText

        gLineX = binding.gCircleLineX
        gLineZ = binding.gCircleLineZ

        gLeftDash = binding.gCircleLeftDash
        gRightDash = binding.gCircleRightDash

        gCircleFrame = binding.gCircleFrame

        muteToggle = binding.muteToggle
        muteToggle.setOnClickListener {
            if (videoCapturer.isMuted) {
                videoCapturer.unmuteRecording()
                setMuteToggleState(muted = false)
                showMessage(R.string.video_audio_recording_unmuted)
            } else {
                videoCapturer.muteRecording()
                setMuteToggleState(muted = true)
                showMessage(R.string.video_audio_recording_muted)
            }
        }
    }

    private fun repositionTabLayout() {
        // The nav no longer floats up to the preview's foot (it used to overlap it): it stays at the very
        // bottom, margin = the navigation-bar inset (see the insets listener), so only the shutter row shows.
        threeButtons.visibility = View.VISIBLE
    }

    fun finalizeMode(picked: CameraMode? = null) {

        // The switcher is untouchable during a recording but not while its start sound still plays, and
        // rebinding the camera there starts the queued recording on a dead recorder. Put the highlight
        // back on the mode the camera is really in.
        if (videoCapturer.isRecording) {
            modeNav.select(camConfig.currentMode)
            return
        }

        val mode = picked ?: modeNav.selected
        if (mode != null) {
            // The highlight has to follow the touch even when the mode does not change, so do not
            // leave this to switchMode(), which returns early for the mode it is already in.
            modeNav.select(mode)
            camConfig.switchMode(mode)
            resetAutoSleep()
        }
    }

    /** Shows the pending self-timer duration on the capture button, where it applies at all. */
    fun updateSelfTimerBadge() {
        cbText.text = if (timerDuration == 0) "" else "${timerDuration}s"
        // isVideoMode covers the video-only activities too, whatever mode they are nominally in.
        val applies = timerDuration != 0 && !camConfig.isQRMode && !camConfig.isVideoMode && !camConfig.isObjectsMode && !camConfig.isVrMode
        cbText.visibility = if (applies) View.VISIBLE else View.INVISIBLE
    }

    fun restartRecordingWithMicPermission() {
        pendingMicRestart = true
        if (!openPerms("Camera: video audio", listOf("microphone"))) resumeAfterPerms()
    }

    private fun shareLatestMedia() {
        if (this is SecureActivity) {
            showMessage(R.string.sharing_not_allowed)
            return
        }

        val item = camConfig.lastCapturedItem
        if (item == null) {
            showMessage(R.string.please_wait_for_image_to_get_captured_before_sharing)
            return
        }

        shareCapturedItem(this, item)?.let { showMessage(it) }
    }

    open fun bytesToHex(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "" // outLen will be wrong for empty inputs

        // Represent bytes as a grid of hex digits:
        // Add a space between every byte
        // Double space every 4 bytes (unless end or newline)
        // Add a newline every 8 bytes (unless end)

        var outLen = bytes.size * 3 - 1 // 2 hex digits + 1 space/newline per byte (except last)
        outLen += bytes.size / 8 // One double space per row except the last incomplete row
        if (bytes.size % 8 > 4) {
            outLen += 1 // One double space for the last incomplete row, if it has >4 columns
        }

        val hexChars = CharArray(outLen)
        var j = 0 // Output index

        for (i in bytes.indices) {
            val byte = bytes[i].toInt() and 0xFF
            hexChars[j++] = hexArray[byte ushr 4]
            hexChars[j++] = hexArray[byte and 0x0F]

            if (i == bytes.lastIndex) break // No trailing whitespace
            if (i % 8 == 7) {
                hexChars[j++] = '\n'
            } else {
                hexChars[j++] = ' '
                if (i % 4 == 3) hexChars[j++] = ' '
            }
        }

        return String(hexChars)
    }

    private var isQRDialogShowing = false

    fun onScanResultSuccess(rawText: String, result: com.google.zxing.Result? = null) {

        if (isQRDialogShowing) return

        // A payload arrived; the quiet-scanner hint is no longer warranted.
        clearNoBarcodeHint()

        isQRDialogShowing = true

        // The decoder hands over a full ZXing Result so the payload can be
        // classified. A decoded value is never shown as a bare string: it is
        // turned into a typed action and the dialog offers what matches.
        val payload = if (result != null) {
            ScannedPayload.fromResult(result)
        } else {
            // Fallback for any caller that only has text (defensive): bare
            // http/https strings are still typed so they can open in the fleet
            // browser; everything else stays copyable plain text.
            ScannedPayload.fromText(rawText)
        }

        runOnUiThread {
            val builder = MaterialAlertDialogBuilder(this)
            val dialogBinding = ScanResultDialogBinding.inflate(layoutInflater)
            builder.setView(dialogBinding.root)

            val textView = dialogBinding.scanResultText
            val openButton = dialogBinding.openWith
            val copyButton = dialogBinding.copyQrText
            val shareButton = dialogBinding.shareQrText
            // There is no Binary/UTF-8 switch: that was a raw-string dump, which is exactly what a
            // typed payload must not be. The value is presented via its action, and the raw text
            // stays copyable below.

            when (payload) {
                is ScannedPayload.Url -> {
                    openButton.visibility = View.VISIBLE
                    openButton.contentDescription = getString(R.string.open_in_fleet_browser)
                    openButton.setImageResource(R.drawable.ic_open_with)
                    openButton.setOnClickListener {
                        openScannedUrl(payload.address)
                    }
                    textView.text = getString(R.string.scanned_url_label_prefix) + payload.address
                }

                is ScannedPayload.Wifi -> {
                    openButton.visibility = View.VISIBLE
                    openButton.contentDescription = getString(R.string.join_wifi_network)
                    openButton.setImageResource(R.drawable.location)
                    openButton.setOnClickListener {
                        // Android cannot silently join a Wi-Fi network from an
                        // app; the honest action is to hand the user to the
                        // system's own join surface with the network in view.
                        startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    }
                    val secured = payload.passwordType.isNotBlank()
                    val prompt = if (secured) {
                        getString(R.string.wifi_credentials_prompt_secured_prefix) +
                            payload.ssid +
                            getString(R.string.wifi_credentials_prompt_secured_suffix) +
                            payload.password.ifBlank { "—" } +
                            getString(R.string.wifi_credentials_prompt_end)
                    } else {
                        getString(R.string.wifi_credentials_prompt_open_prefix) +
                            payload.ssid +
                            getString(R.string.wifi_credentials_prompt_open_suffix)
                    }
                    textView.text = prompt
                }

                is ScannedPayload.Contact -> {
                    openButton.visibility = View.VISIBLE
                    openButton.contentDescription = getString(R.string.add_contact)
                    openButton.setImageResource(R.drawable.info)
                    openButton.setOnClickListener {
                        val contactIntent = Intent(Intent.ACTION_INSERT).apply {
                            type = ContactsContract.Contacts.CONTENT_TYPE
                            val displayName = payload.names.joinToString(" ")
                            if (displayName.isNotBlank()) {
                                putExtra(ContactsContract.Intents.Insert.NAME, displayName)
                            }
                            if (payload.phoneNumbers.isNotEmpty()) {
                                putExtra(ContactsContract.Intents.Insert.PHONE, payload.phoneNumbers[0])
                            }
                            if (payload.emails.isNotEmpty()) {
                                putExtra(ContactsContract.Intents.Insert.EMAIL, payload.emails[0])
                            }
                        }
                        runCatching { startActivity(contactIntent) }
                            .onFailure { showMessage(getString(R.string.no_contact_intent_handler)) }
                    }
                    textView.text = ScannedPayload.describeContact(payload)
                }

                is ScannedPayload.CalendarEvent -> {
                    openButton.visibility = View.VISIBLE
                    openButton.contentDescription = getString(R.string.add_calendar_event)
                    openButton.setImageResource(R.drawable.exposure_plus)
                    openButton.setOnClickListener {
                        val eventIntent = Intent(Intent.ACTION_INSERT).apply {
                            data = CalendarContract.Events.CONTENT_URI
                            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, payload.startMillis)
                            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, payload.endMillis)
                            if (payload.title.isNotBlank()) {
                                putExtra(CalendarContract.Events.TITLE, payload.title)
                            }
                            if (payload.location.isNotBlank()) {
                                putExtra(CalendarContract.Events.EVENT_LOCATION, payload.location)
                            }
                            if (payload.description.isNotBlank()) {
                                putExtra(CalendarContract.Events.DESCRIPTION, payload.description)
                            }
                        }
                        runCatching { startActivity(eventIntent) }
                            .onFailure { showMessage(getString(R.string.no_calendar_intent_handler)) }
                    }
                    textView.text = ScannedPayload.describeCalendar(payload)
                }

                is ScannedPayload.PlainText -> {
                    // Any other payload is still shown and copied — the value is
                    // never lost, it just has no richer action to offer.
                    openButton.visibility = View.GONE
                    textView.autoLinkMask = Linkify.WEB_URLS or Linkify.PHONE_NUMBERS or Linkify.EMAIL_ADDRESSES
                    textView.setTextIsSelectable(true)
                    textView.text = payload.text.ifBlank { rawText }
                }
            }

            copyButton.setOnClickListener {
                val clipboardManager = getSystemService(
                    Context.CLIPBOARD_SERVICE
                ) as ClipboardManager
                val clipData = ClipData.newPlainText("text", textView.text)
                clipboardManager.setPrimaryClip(clipData)
                showMessage(getString(R.string.copied_text_to_clipboard))
            }

            shareButton.setOnClickListener {
                val sIntent = Intent(Intent.ACTION_SEND)
                sIntent.type = "text/plain"
                sIntent.putExtra(Intent.EXTRA_TEXT, textView.text.toString())
                startActivity(
                    Intent.createChooser(
                        sIntent,
                        getString(R.string.share_text_via)
                    )
                )
            }

            builder.setOnDismissListener {
                isQRDialogShowing = false
                camConfig.startCamera(true)
            }

            camConfig.cameraProvider?.unbindAll()

            builder.showIgnoringShortEdgeMode()
        }
    }

    /**
     * Open [url] inside the fleet's own browser surface (#156), never a bare
     * ACTION_VIEW that hands control to whatever app claims the scheme. The
     * fleet browser is wrapped in an explicit package so the URL stays in the
     * constellation; if it is not installed the user is told so with a path to
     * the fleet installer, and the link stays copyable.
     */
    private fun openScannedUrl(url: String) {
        if (!ScannedPayload.usesWebScheme(url)) {
            showMessage(getString(R.string.cannot_open_non_web_link))
            return
        }
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(FLEET_BROWSER_PACKAGE)
        }
        if (packageManager.resolveActivity(browserIntent, 0L) != null) {
            runCatching { startActivity(browserIntent) }
                .onFailure { showMessage(getString(R.string.cannot_open_scanned_url)) }
        } else {
            showMessage(getString(R.string.fleet_browser_not_installed))
        }
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        if (event.action == MotionEvent.ACTION_DOWN) return true else if (event.action == MotionEvent.ACTION_UP) {

            if (wasSwiping) {
                wasSwiping = false
                return wasSwiping
            }

            if (isZooming) {
                isZooming = false
                return true
            }

            if (camConfig.isQRMode)
                return false

            val x = event.x
            val y = event.y

            val autoFocusPoint = previewView.meteringPointFactory.createPoint(x, y)
            animateFocusRing(x, y)

            val focusBuilder = FocusMeteringAction.Builder(autoFocusPoint)

            if (!camConfig.isVideoMode) {
                camConfig.mPlayer.playFocusStartSound()
            }

            if (camConfig.focusTimeout == 0L) {
                focusBuilder.disableAutoCancel()
            } else {
                focusBuilder.setAutoCancelDuration(camConfig.focusTimeout, TimeUnit.SECONDS)
            }

            camConfig.camera!!.cameraControl.startFocusAndMetering(focusBuilder.build())

            exposureBar.showPanel()
            zoomBar.showPanel()
            return v.performClick()
        }
        return true
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        isZooming = true
        val zoomState = camConfig.zoomState
        var scale = 1f
        if (zoomState != null) {
            scale = zoomState.zoomRatio * detector.scaleFactor
        }
        camConfig.camera!!.cameraControl.setZoomRatio(scale)
        return true
    }

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        return true
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) {}

    private fun rotateView(view: View?, angle: Float) {
        if (view != null) {
            view.animate().cancel()

            // Ensuring that the rotation seems continuous
            if (view.rotation == 0f && angle == 270f)
                view.rotation = 360f

            if (view.rotation == 270f && angle == 0f)
                view.rotation = -90f

            view.animate()
                .rotation(angle)
                .setDuration(400)
                .setInterpolator(LinearInterpolator())
                .start()
        }
    }

    @SuppressLint("RestrictedApi")
    override fun onOrientationChange(orientation: Int) {

        val tr = when (orientation) {
            in 45..134 -> Surface.ROTATION_270
            in 135..224 -> Surface.ROTATION_180
            in 225..314 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }

        camConfig.imageCapture?.targetRotation = tr
        camConfig.videoCapture?.targetRotation = tr
        camConfig.iAnalyzer?.targetRotation = tr

        if (videoCapturer.isRecording) return

        var iconRotation = (360f - ((orientation - getRotation() + 360) % 360)) % 360

        // Rotate views that should rotate irrespective of the auto-rotate setting
        rotateView(gCircleFrame, iconRotation)

        // Set iconRotation to 0
        if (Settings.System.getInt(
                contentResolver,
                Settings.System.ACCELEROMETER_ROTATION, 0
            ) != 1
        ) {
            iconRotation = 0f
        }

        // Rotate views that shouldn't be affected by the auto rotate setting
        // (Rotates back to 0 when the auto rotate gets toggled to off when the app
        // is running)
        rotateView(flipCameraCircle, iconRotation)
        rotateView(cancelButtonView, iconRotation)
        rotateView(thirdOption, iconRotation)
        // Sits in the same column as thirdOption and must turn with it, otherwise the
        // folder glyph stays upright while every icon around it rotates.
        rotateView(openCloudGalleryVideo, iconRotation)
        rotateView(openCloudGalleryPhoto, iconRotation)

        rotateView(exposurePlusIcon, iconRotation)
        rotateView(exposureNegIcon, iconRotation)
        rotateView(zoomInIcon, iconRotation)
        rotateView(zoomOutIcon, iconRotation)
        rotateView(settingsDialog.settingsFrame, iconRotation)

        rotateView(micOffIcon, iconRotation)
        rotateView(muteToggle, iconRotation)
    }

    lateinit var camConfig: CamConfig
    private lateinit var cameraControl: CameraControl

    companion object {
        private const val TAG = "GOCam"
        // The fleet's own browser surface (#156). URL opens are pinned to this
        // package so a scanned link stays inside the constellation instead of
        // being handed to an arbitrary ACTION_VIEW handler that leaves the app.
        private const val FLEET_BROWSER_PACKAGE = "com.diegonmarcos.cloudbrowser"
        // How long the scanner waits with nothing decoded before it says so.
        // Long enough to let a code actually enter the frame, short enough that
        // a silent empty box never reads as availability.
        private const val NO_BARCODE_HINT_DELAY_MS = 3_000L
        private const val autoCenterFocusDuration = 2000L
        private val hexArray = "0123456789ABCDEF".toCharArray()

        private const val SWIPE_THRESHOLD = 100
        private const val SWIPE_VELOCITY_THRESHOLD = 100

        private const val GYRO_VIBE_WAIT_TIME = 250L
    }

    override fun onDown(e: MotionEvent): Boolean {
        return false
    }

    override fun onShowPress(e: MotionEvent) {}

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        return false
    }

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        return false
    }

    override fun onLongPress(e: MotionEvent) {}

    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean {

        var result = false
        try {
            e1 ?: return false

            val diffY = e2.y - e1.y
            val diffX = e2.x - e1.x

            if (abs(diffX) > abs(diffY)) {
                if (abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) {
                        onSwipeRight()
                    } else {
                        onSwipeLeft()
                    }
                    result = true
                }
            } else if (abs(diffY) > SWIPE_THRESHOLD && abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                if (diffY > 0) {
                    onSwipeBottom()
                } else {
                    onSwipeTop()
                }
                result = true
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
        }
        return result
    }

    private fun onSwipeBottom() {
        if (isZooming || cdTimer.isRunning) return
        wasSwiping = true
        if (settingsDialog.isShowing) return

        if (camConfig.isQRMode) {
            if (!camConfig.scanAllCodes) {
                camConfig.showMoreOptionsForQR()
            }
        } else {
            if (settingsIcon.isEnabled) {
                settingsIcon.performClick()
            }
        }
    }

    private fun onSwipeRight() {

        if (isZooming || cdTimer.isRunning || videoCapturer.isRecording)
            return

        if (this is VideoOnlyActivity) return

        wasSwiping = true
        if (settingsDialog.isShowing) return


        val i = modeNav.selectedIndex - 1

        Log.i(TAG, "onSwipeRight $i")
        modeNav.modeAt(i)?.let {
            finalizeMode(it)
        }
    }

    private fun onSwipeTop() {
        if (isZooming || cdTimer.isRunning || videoCapturer.isRecording) return
        wasSwiping = true
        settingsDialog.slideDialogUp()
    }

    private fun onSwipeLeft() {
        if (isZooming || cdTimer.isRunning || videoCapturer.isRecording) return

        if (this is VideoOnlyActivity) return

        wasSwiping = true
        if (settingsDialog.isShowing) return

        val i = modeNav.selectedIndex + 1
        modeNav.modeAt(i)?.let {
            finalizeMode(it)
        }
    }

    override fun onSingleTapConfirmed(p0: MotionEvent): Boolean {
        return false
    }

    override fun onDoubleTap(p0: MotionEvent): Boolean {
        return false
    }

    override fun onDoubleTapEvent(p0: MotionEvent): Boolean {
        return false
    }

    fun showMessage(@StringRes msg: Int, action: String? = null, callback: View.OnClickListener? = null) {
        showMessage(getString(msg), action, callback)
    }

    /**
     * The icon in the left circle stands for whatever [flipCameraCircle] does right now — flip
     * the camera, pause/resume the recording, or toggle scanning of every barcode format. Swap
     * its description together with its drawable so that it is never announced as the wrong
     * button.
     */
    fun setFlipCameraIcon(@DrawableRes icon: Int, @StringRes description: Int) {
        flipCamIcon.setImageResource(icon)
        flipCamIcon.contentDescription = getString(description)
    }

    /**
     * The big middle button is a shutter, a record/stop button and a torch switch depending on
     * the mode, so its description has to travel with its drawable exactly like the one in
     * [setFlipCameraIcon] does.
     */
    fun setCaptureButtonIcon(@DrawableRes icon: Int, @StringRes description: Int) {
        captureButton.setImageResource(icon)
        captureButton.contentDescription = getString(description)
    }

    /**
     * [thirdCircle] is the view that carries the click listener, so it is the one that has to be
     * described: it opens the gallery, except while a video is being recorded, when it takes a
     * still instead.
     */
    fun setThirdCircleIcon(@DrawableRes icon: Int, @StringRes description: Int) {
        thirdCircle.setImageResource(icon)
        thirdCircle.contentDescription = getString(description)
    }

    /**
     * The mute toggle signals its state through an icon, a background colour and a tooltip, and a
     * tooltip is supplementary text rather than a view's label, so the state was never described
     * to accessibility services at all. Set all four together here so they cannot drift apart.
     */
    fun setMuteToggleState(muted: Boolean) {
        muteToggle.setImageResource(if (muted) R.drawable.mic_off else R.drawable.mic_on)
        muteToggle.setBackgroundColor(
            if (muted) getColor(android.R.color.darker_gray) else getColor(R.color.red)
        )
        muteToggle.tooltipText = getString(
            if (muted) R.string.tap_to_unmute_audio else R.string.tap_to_mute_audio
        )
        muteToggle.contentDescription = getString(
            if (muted) R.string.unmute_audio else R.string.mute_audio
        )
    }

    fun showMessage(msg: String, action: String? = null, callback: View.OnClickListener? = null) {
        snackBar.apply {
            setText(msg)
            setAction(action, callback)
            show()
        }
    }


    fun indicateLocationProvidedIsDisabled() {
        showMessage(getString(R.string.location_is_disabled),
            if (this !is SecureMainActivity) getString(R.string.enable) else null
        ) {
            enableLocationLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // The activity declares configChanges for orientation, so nothing else refreshes
        // rotation-dependent state.
        // The preview follows the window; the capture use cases follow the sensor and are updated
        // by onOrientationChange.
        camConfig.preview?.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        camConfig.camera?.cameraInfo?.let {
            previewView.applyPreviewRatio(camConfig.aspectRatio, it)
        }

        rootView.post { sensorNotifier?.notifyListeners() }
    }

    private fun pauseOrientationSensor() {
        SensorOrientationChangeNotifier
            .getInstance(this)?.remove(this)
    }

    private fun resumeOrientationSensor() {
        sensorNotifier?.addListener(this)
    }

    fun forceUpdateOrientationSensor() {
        sensorNotifier?.notifyListeners(true)
    }

    val sensorNotifier: SensorOrientationChangeNotifier?
        get() {
            return SensorOrientationChangeNotifier.getInstance(this)
        }

    fun getRotation(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

        return when (rotation) {
            Surface.ROTATION_90 -> 270
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 90
            else -> 0
        }
    }

    fun onDeviceAngleChange(xDegrees: Float, zDegrees: Float) {

        val reverseDirection = sensorNotifier?.mOrientation == 270 || sensorNotifier?.mOrientation == 180

        val xAngle = if (reverseDirection) {
            -xDegrees
        } else {
            xDegrees
        }

        val zAngle = zDegrees

        // If we are in photo mode and the countdown timer isn't running
        if (!(camConfig.isQRMode || camConfig.isVideoMode || cdTimer.isRunning)) {

            if (gCircle.rotation != xAngle) {
                gCircle.rotation = xAngle
                gLineZ.rotation = xAngle

                val absXAngle = abs(xAngle).toInt()

                gAngleTextView.text = getString(R.string.degree_format, absXAngle)
                if (xAngle == 0f) {
                    setThicknessOfGLines(4)
                    if (shouldGyroVibrate) {
                        shouldGyroVibrate = false
                        hasGyroVibrated = false
                        handler.postDelayed(gyroVibRunnable, GYRO_VIBE_WAIT_TIME)
                    }
                } else {
                    handler.removeCallbacks(gyroVibRunnable)
                    if (!hasGyroVibrated || absXAngle > 5) {
                        shouldGyroVibrate = true
                    }
                    setThicknessOfGLines(2)
                }
            }

            Log.i(TAG, "zAngle: $zAngle")

            val lzAngle = when {
                zAngle < -45 -> {
                    -45
                }
                zAngle > 45 -> {
                    45
                }
                else -> {
                    zAngle
                }
            }.toFloat()

            if (zAngle.toInt() == 0) {
                gLineX.setBackgroundResource(R.drawable.yellow_shadow_rect)
                gLineZ.visibility = View.GONE

                gLeftDash.setBackgroundResource(R.drawable.yellow_shadow_rect)
                gRightDash.setBackgroundResource(R.drawable.yellow_shadow_rect)

                gAngleTextView.setTextColor(ContextCompat.getColor(this, R.color.z_yellow))

            } else {
                gLineX.setBackgroundResource(R.drawable.white_shadow_rect)
                gLineZ.visibility = View.VISIBLE

                gLeftDash.setBackgroundResource(R.drawable.white_shadow_rect)
                gRightDash.setBackgroundResource(R.drawable.white_shadow_rect)

                gAngleTextView.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            }

            val zOffset = (lzAngle / 60) * dp32

            gLineZ.layoutParams = (gLineZ.layoutParams as ViewGroup.MarginLayoutParams).let {
                it.setMargins(
                    it.leftMargin,
                    it.topMargin,
                    it.rightMargin,
                    zOffset.toInt(),
                )
                it
            }
        }
    }

    private val dp32 by lazy {
        32 * resources.displayMetrics.density
    }

    private fun setThicknessOfGLines(dp: Int) {
        val t = dp * resources.displayMetrics.density

        gLeftDash.layoutParams = gLeftDash.layoutParams.let {
            it.height = t.toInt()
            it
        }

        gRightDash.layoutParams = gRightDash.layoutParams.let {
            it.height = t.toInt()
            it
        }

        gLineX.layoutParams = gLineX.layoutParams.let {
            it.height = t.toInt()
            it
        }
    }

    private fun vibrateDevice() {
        val vibrator = getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }

    override fun onDestroy() {
        super.onDestroy()
        SensorOrientationChangeNotifier.clearInstance()
        thumbnailLoaderExecutor.shutdownNow()
    }

    fun locationCamConfigChanged(required: Boolean) {
        if (required) {
            requestLocation()
        } else {
            application.disableLocationFetching()
        }
    }

    private val enableLocationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // The snackbar that leads here outlives a mode switch, so geo-tagging can be off for the
        // mode this returns to
        if (camConfig.requireLocation) {
            requestLocation(application.isAnyLocationProvideActive())
        }
    }

    /** #1515 back from Configs > Perms without the location grant (or asked already): geotagging goes off, as when the
     *  system dialog was refused; with it, location updates start. */
    private fun onLocationPermsResult() {
        if (!application.shouldAskForLocationPermission()) {
            application.requestLocationUpdates(false)
        } else {
            camConfig.requireLocation = false
        }
    }

    private fun requestLocation(reAttach: Boolean = false) {

        when {
            deniedOnce("fine_location", "coarse_location") -> {
                MaterialAlertDialogBuilder(this).let {
                    it.setTitle(R.string.location_permission_dialog_title)
                    it.setMessage(R.string.location_permission_dialog_message)

                    if (this !is SecureActivity) {
                        // #1515 the location grants live in Configs > Perms: open it on those rows
                        it.setPositiveButton(R.string.settings) { _, _ ->
                            runCatching { com.diegonmarcos.superapp.perms.PermsLink.open(this, "Camera: geotag", listOf("fine_location", "coarse_location")) }
                        }
                    }

                    it.setOnDismissListener {
                        if (ContextCompat.checkSelfPermission(
                                this, Manifest.permission.ACCESS_COARSE_LOCATION
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            camConfig.requireLocation = false
                        }
                    }
                }.showIgnoringShortEdgeMode()
            }

            (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                    || ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
                    == PackageManager.PERMISSION_GRANTED) -> {
                application.requestLocationUpdates(reAttach)
            }
            else -> {
                // #1515 asked in Configs > Perms (once per screen); back here without it, geotagging stays off.
                // The camera grant goes first: location is asked on the resume after it is held.
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
                if (!openPerms("Camera: geotag", listOf("coarse_location", "fine_location"))) onLocationPermsResult()
            }
        }
    }

    private fun resetAutoSleep() {
        application.resetPreventScreenFromSleeping()
    }

    @Volatile var isStarted = false

    override fun onStart() {
        super.onStart()
        isStarted = true
    }

    override fun onStop() {
        isStarted = false
        // Stop explicitly rather than letting the unbind tear the recording down for us.
        if (this::videoCapturer.isInitialized && videoCapturer.isRecording) {
            updateLastFrame()
            videoCapturer.stopRecording()
        }
        super.onStop()
    }

    var isThumbnailLoaded = false

    fun updateThumbnail() {
        val item = camConfig.lastCapturedItem
        val preview = imagePreview
        preview.setImageBitmap(null)
        isThumbnailLoaded = false

        if (item == null) {
            return
        }

        val ctx = applicationContext

        thumbnailLoaderExecutor.executeIfAlive {
            var bitmap: Bitmap? = null
            try {
                val side = preview.layoutParams.width

                if (item.type == ITEM_TYPE_VIDEO) {
                    val origBitmap = getVideoThumbnail(ctx, item.uri)
                    origBitmap?.let {
                        val w = it.width.toDouble()
                        val h = it.height.toDouble()
                        val ratio = max(w / side, h / side)

                        bitmap = Bitmap.createScaledBitmap(it, (w / ratio).toInt(), (h / ratio).toInt(), true)
                        origBitmap.recycle()
                    }
                } else if (item.type == ITEM_TYPE_IMAGE) {
                    val source = ImageDecoder.createSource(ctx.contentResolver, item.uri)
                    bitmap = ImageDecoder.decodeBitmap(source, ImageResizer(side, side))
                }
            } catch (e: Exception) {
                Log.d(TAG, "unable to update preview", e)
            }

            if (bitmap != null) {
                mainExecutor.execute {
                    if (isStarted && camConfig.lastCapturedItem == item) {
                        preview.setImageBitmap(bitmap)
                        isThumbnailLoaded = true
                    }
                }
            }
        }
    }

    open fun shouldShowCameraModeTabs() = true

    /**
     * What [thirdOption] looks like when nothing special is happening.
     *
     * The big circle in the bottom right is gone from the idle camera screen: the two Media
     * Center buttons under it open the same folder, so it had become a third way to do what
     * two buttons already do. The frame stays in the layout because the gallery was never its
     * only job — while a video is recording it carries the take-a-still shutter, and the
     * capture-intent subclasses use the same frame as the preview they hand back to the app
     * that asked for a capture.
     *
     * That leaves several places — the countdown finishing, a QR session ending, a recording
     * stopping — that all mean "put the normal camera UI back", and normal is not the same
     * thing in every activity. Each of them asks here instead of hardcoding VISIBLE, so the
     * circle cannot come back on the idle screen through a path nobody thought about.
     * [CaptureActivity] overrides this: the capture-intent screens keep the 96dp reserved so
     * the preview does not shove the buttons under it when it appears.
     */
    open val thirdOptionIdleVisibility: Int get() = View.GONE

    private fun restartRecordingIfPermissionsWasUnavailable() {
        if (shouldRestartRecording) {
            shouldRestartRecording = false
            videoCapturer.startRecording()
        }
    }
}

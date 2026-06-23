package com.Twinhealth.pulseview

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX-based front-camera capture pipeline.
 *
 * Delivers a [List<Mat>] of 3-channel RGB OpenCV Mats suitable for direct
 * consumption by [Preprocessor.detectFaceBox] and [Preprocessor.cropAndResize].
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * COLOR FORMAT CHAIN
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * CameraX ImageAnalysis is configured with OUTPUT_IMAGE_FORMAT_RGBA_8888.
 * This means CameraX internally converts the sensor's YUV_420_888 data to RGBA
 * before delivering it to our analyzer. We never see raw YUV.
 *
 * WHY RGBA_8888 INSTEAD OF RAW YUV_420_888:
 *   Hand-rolling a correct YUV→RGB conversion requires choosing the right color
 *   matrix (BT.601 vs BT.709 vs BT.2020) and chroma upsampling filter. Getting
 *   this wrong produces the systematic ~7 DN color error we observed when
 *   comparing Python/FFmpeg output against Android MediaCodec output during
 *   Preprocessor validation. By requesting RGBA_8888, we delegate the conversion
 *   to CameraX's own well-tested code path and avoid re-introducing that bug.
 *
 * CONVERSION PATH:
 *   ImageProxy (RGBA_8888, 1 plane, bytes in true RGBA order)
 *     → planes[0].buffer → ByteArray                          (raw RGBA bytes)
 *     → Mat(h, w, CV_8UC4).put(0, 0, bytes)                  (4-ch RGBA Mat)
 *     → Imgproc.cvtColor(..., COLOR_RGBA2RGB)                 (3-ch RGB Mat)
 *
 *   The final Mat has channel layout [R=ch0, G=ch1, B=ch2], matching
 *   Preprocessor.detectFaceBox() (COLOR_RGB2GRAY) and cropAndResize().
 *
 * ⚠ WHY COLOR_RGBA2RGB AND NOT COLOR_BGRA2BGR (as in the validation test):
 *   In PreprocessorVideoValidationTest, we loaded PNGs via BitmapFactory and
 *   Utils.bitmapToMat(). Android Bitmap ARGB_8888 stores bytes as [R,G,B,A]
 *   in memory, but bitmapToMat() labels the channels BGRA — so ch0 contained
 *   visual-R, not visual-B. Using COLOR_BGRA2RGB would swap ch0↔ch2 again,
 *   producing a double-swap. We fixed it with COLOR_BGRA2BGR (drop alpha only).
 *
 *   HERE: we construct the Mat directly from the CameraX buffer via Mat.put().
 *   The bytes are truly [R,G,B,A] and OpenCV sees them as channel [0,1,2,3]
 *   = [R,G,B,A]. COLOR_RGBA2RGB therefore correctly maps ch0→R, ch1→G, ch2→B.
 *   No double-swap issue.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * MEMORY BUDGET
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * At 850×478 resolution (native front camera on test device), each RGB Mat is:
 *   850 × 478 × 3 bytes ≈ 1.22 MB
 *
 * MAX_FRAMES = 900 (30 s × 30 fps) → peak ≈ 1.1 GB resident.
 * This is within the headroom of the Samsung Galaxy S24 (12 GB RAM) but would
 * OOM on a 4 GB device. For mid-range device support, consider option (b) from
 * the implementation plan (per-frame crop before storing).
 *
 * DESIGN CHOICE: We do NOT crop per-frame before storing. The validated pipeline
 * runs face detection once on frame 0 and uses a fixed crop region for the entire
 * clip, matching the Python reference. Per-frame crop would deviate from that.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * FRAME RATE THROTTLING
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * The camera sensor delivers frames at whatever rate it chooses (typically
 * 15–120 fps depending on device, lighting, and exposure mode). We throttle
 * to TARGET_FPS (30) by dropping frames that arrive too quickly. We do NOT
 * insert synthetic duplicate frames if the camera runs slower than 30 fps —
 * we just accept what we get and log the actual achieved FPS at stop time.
 *
 * The 30 fps target matches FS=30.0 hardcoded in the Python pipeline's
 * heart-rate calculation. A significant deviation (say < 20 fps) would bias
 * the HR estimate; the log warning makes this visible.
 */
class CameraCapture(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "CameraCapture"

        /** Target capture rate matching Python pipeline's FS = 30.0. */
        private const val TARGET_FPS = 30
        private val TARGET_INTERVAL_NS = 1_000_000_000L / TARGET_FPS  // ~33.3 ms

        /**
         * Hard cap on stored frames = 30 seconds at 30 fps.
         * See class KDoc for memory budget rationale.
         */
        const val MAX_FRAMES = 900

        /** FPS deviation threshold that triggers a logcat warning. */
        private const val FPS_WARN_LOW  = 20.0
        private const val FPS_WARN_HIGH = 40.0
    }

    // ── Frame storage (thread-safe) ───────────────────────────────────────────

    // ImageAnalysis delivers frames on a single background executor thread, but
    // stopRecording() is called from the UI thread, so we need synchronization.
    private val _frames: MutableList<Mat> = Collections.synchronizedList(mutableListOf())

    private val _frameCount = MutableStateFlow(0)

    /** Live frame count — observe in Compose UI to show recording progress. */
    val frameCount: StateFlow<Int> = _frameCount.asStateFlow()

    // ── Internal state ────────────────────────────────────────────────────────

    /** Toggled by startRecording() / stopRecording(). Volatile via AtomicBoolean. */
    private val isRecording = AtomicBoolean(false)

    /** Nanosecond timestamp of the last frame we actually kept (0 = none yet). */
    @Volatile private var lastCapturedTimeNs: Long = 0L

    /** Nanosecond timestamp of when startRecording() was called. */
    @Volatile private var recordingStartTimeNs: Long = 0L

    /** Single-threaded executor for ImageAnalysis callbacks. */
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null

    // ── Camera binding ────────────────────────────────────────────────────────

    /**
     * Bind CameraX [Preview] + [ImageAnalysis] to the given [previewView].
     *
     * Must be called from the main thread (uses [ContextCompat.getMainExecutor]).
     * The camera is bound to [lifecycleOwner] so it auto-stops when the
     * composable leaves composition.
     */
    fun bindCamera(previewView: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            // ── Preview use case — feeds the on-screen viewfinder ─────────
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            // ── ImageAnalysis use case — delivers raw RGBA frames ──────────
            val imageAnalysis = ImageAnalysis.Builder()
                // Request RGBA_8888: CameraX converts YUV→RGBA internally.
                // Rationale: see class-level KDoc ("WHY RGBA_8888 INSTEAD OF RAW YUV").
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                // KEEP_ONLY_LATEST: drop any un-analyzed frame when a new one arrives.
                // This prevents the analysis queue from growing unboundedly if processing
                // falls behind the sensor rate, while our throttle handles the 30 fps cap.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(analysisExecutor, ::analyzeFrame)

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalysis
                )
                Log.i(TAG, "Camera bound: DEFAULT_FRONT_CAMERA, output=RGBA_8888")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // ── Frame analyzer ────────────────────────────────────────────────────────

    /**
     * Called by CameraX on [analysisExecutor] for every delivered frame.
     *
     * Throttles to [TARGET_FPS], enforces [MAX_FRAMES] cap, and converts
     * RGBA ImageProxy → RGB Mat via [imageProxyToRgbMat].
     *
     * Contract: [imageProxy] MUST be closed before this function returns —
     * failure to do so starves the CameraX buffer pool and freezes the preview.
     */
    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            if (!isRecording.get()) {
                return  // not recording — discard immediately (finally closes proxy)
            }

            // ── Frame rate throttle ──────────────────────────────────────
            val nowNs = System.nanoTime()
            if (nowNs - lastCapturedTimeNs < TARGET_INTERVAL_NS) {
                return  // frame arrived too soon — drop it
            }

            // ── Memory cap ───────────────────────────────────────────────
            if (_frames.size >= MAX_FRAMES) {
                Log.w(TAG, "MAX_FRAMES ($MAX_FRAMES / ${MAX_FRAMES / TARGET_FPS}s) reached — auto-stopping capture")
                isRecording.set(false)
                return
            }

            lastCapturedTimeNs = nowNs

            // ── Convert and store ────────────────────────────────────────
            val rgbMat = imageProxyToRgbMat(imageProxy)
            _frames.add(rgbMat)
            _frameCount.value = _frames.size

        } finally {
            // MUST always close — CameraX recycles the buffer only after close().
            // If we forget this, the preview freezes within a few frames.
            imageProxy.close()
        }
    }

    /**
     * Convert a CameraX [ImageProxy] (RGBA_8888 format) to a 3-channel RGB [Mat].
     *
     * See class KDoc ("CONVERSION PATH") for the full color-format analysis and
     * the explanation of why this uses [Imgproc.COLOR_RGBA2RGB] (not BGRA2BGR).
     *
     * @return A new [Mat] of type CV_8UC3 in RGB channel order. Caller owns it.
     */
    private fun imageProxyToRgbMat(imageProxy: ImageProxy): Mat {
        val w = imageProxy.width
        val h = imageProxy.height

        // planes[0] is the single interleaved RGBA plane for RGBA_8888 format.
        // Buffer capacity = w × h × 4 bytes.
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        // Build a 4-channel Mat from the raw bytes.
        // CV_8UC4 = 8-bit unsigned, 4 channels. Mat channels = [R, G, B, A].
        val rgbaMat = Mat(h, w, CvType.CV_8UC4)
        rgbaMat.put(0, 0, bytes)

        // Drop alpha channel: RGBA → RGB.
        // COLOR_RGBA2RGB: output[0]=input[0]=R, output[1]=input[1]=G, output[2]=input[2]=B
        // Result: CV_8UC3 Mat, channels [R=ch0, G=ch1, B=ch2] ✓
        val rgbMat = Mat()
        Imgproc.cvtColor(rgbaMat, rgbMat, Imgproc.COLOR_RGBA2RGB)
        rgbaMat.release()

        return rgbMat
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start capturing frames.
     *
     * Clears any previously captured frames, resets the frame counter, and
     * enables the [analyzeFrame] pipeline. Safe to call from the UI thread.
     */
    fun startRecording() {
        synchronized(_frames) { _frames.clear() }
        _frameCount.value = 0
        lastCapturedTimeNs = 0L
        recordingStartTimeNs = System.nanoTime()
        isRecording.set(true)
        Log.i(TAG, "Recording started — target: ${TARGET_FPS} fps, cap: $MAX_FRAMES frames (${MAX_FRAMES / TARGET_FPS}s)")
    }

    /**
     * Stop capturing and return all captured frames.
     *
     * Logs achieved FPS vs the [TARGET_FPS] assumption baked into the Python
     * pipeline's HR calculation. A significant deviation (< [FPS_WARN_LOW] or
     * > [FPS_WARN_HIGH]) triggers a logcat warning.
     *
     * **Caller owns the returned [Mat]s and is responsible for calling [Mat.release]
     * on each when done.** Releasing them promptly is important — at ~1.2 MB each,
     * 900 frames ≈ 1.1 GB of native OpenCV memory.
     *
     * @return Immutable snapshot of captured RGB Mats in chronological order.
     */
    fun stopRecording(): List<Mat> {
        isRecording.set(false)

        val elapsedSec = (System.nanoTime() - recordingStartTimeNs) / 1_000_000_000.0
        val numFrames: Int
        val snapshot: List<Mat>

        synchronized(_frames) {
            numFrames = _frames.size
            snapshot = _frames.toList()
            _frames.clear()
        }
        _frameCount.value = 0

        val achievedFps = if (elapsedSec > 0.0) numFrames / elapsedSec else 0.0
        Log.i(TAG, "=== Recording stopped: $numFrames frames in %.1fs → %.1f fps (target: $TARGET_FPS) ===".format(elapsedSec, achievedFps))

        if (achievedFps < FPS_WARN_LOW || (achievedFps > FPS_WARN_HIGH && numFrames > 10)) {
            Log.w(TAG, "Achieved FPS (%.1f) differs significantly from target ($TARGET_FPS). ".format(achievedFps) +
                    "Heart-rate calculation assumes FS=${TARGET_FPS}.0 — results may be inaccurate.")
        }

        return snapshot
    }

    /**
     * Unbind the camera and shut down the analysis executor.
     *
     * Call this from [DisposableEffect.onDispose] in the Compose UI to avoid
     * keeping the camera open after the screen leaves composition.
     */
    fun unbind() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        Log.i(TAG, "Camera unbound")
    }
}

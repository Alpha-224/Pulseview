package com.Twinhealth.pulseview

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import com.Twinhealth.pulseview.ui.theme.*
import org.opencv.core.Mat
import org.opencv.objdetect.CascadeClassifier
import java.io.File
import java.io.FileOutputStream

private const val TAG = "CameraScreen"
private const val FIXED_RECORDING_DURATION_SECONDS = 25.0

/**
 * Main camera recording screen.
 *
 * Dark mode layout with teal/navy scheme matching the web app.
 *
 * When the user stops recording, this screen runs the full rPPG inference
 * pipeline on a background thread:
 *   1. Preprocessor.preprocessToChunks() — face detect, crop, diff-normalize, chunk
 *   2. OnnxInference.runInference() — per-chunk BVP prediction
 *   3. FftHrCalculator.computeHr() — FFT-based heart rate extraction
 *
 * A loading overlay is shown during inference. On completion, [onInferenceComplete]
 * is called with the real computed results.
 *
 * @param onInferenceComplete Called with real inference results when the pipeline finishes.
 */
@Composable
fun CameraScreen(onInferenceComplete: (InferenceResult) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraCapture = remember { CameraCapture(context, lifecycleOwner) }
    val frameCount by cameraCapture.frameCount.collectAsState()

    var isRecording by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var recordingStartTimeNs by remember { mutableStateOf(0L) }
    var elapsedSeconds by remember { mutableStateOf(0.0) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(isRecording) {
        if (isRecording) {
            val startTime = System.nanoTime()
            while (isRecording) {
                val currentElapsed = (System.nanoTime() - startTime) / 1_000_000_000.0
                if (currentElapsed >= FIXED_RECORDING_DURATION_SECONDS) {
                    elapsedSeconds = FIXED_RECORDING_DURATION_SECONDS
                    isRecording = false
                    
                    // Stop recording and run pipeline
                    isProcessing = true
                    val recordingEndTimeNs = System.nanoTime()
                    val frames = cameraCapture.stopRecording()
                    val numFrames = frames.size

                    if (numFrames == 0) {
                        statusText = "No frames captured."
                        isProcessing = false
                        break
                    }

                    val elapsedSec = (recordingEndTimeNs - recordingStartTimeNs) / 1_000_000_000.0
                    val achievedFps = if (elapsedSec > 0) numFrames / elapsedSec else 0.0

                    Log.i(TAG, "Auto-stopped: Captured $numFrames frames in %.1fs (%.1f fps)".format(elapsedSec, achievedFps))

                    coroutineScope.launch {
                        val result = runInferencePipeline(
                            context = context,
                            frames = frames,
                            numFrames = numFrames,
                            recordingDurationSeconds = elapsedSec,
                            achievedFps = achievedFps
                        )

                        if (result != null) {
                            onInferenceComplete(result)
                        } else {
                            isProcessing = false
                            statusText = "Inference failed — check Logcat for details."
                        }
                    }
                    break
                }
                elapsedSeconds = currentElapsed
                kotlinx.coroutines.delay(100)
            }
        } else {
            elapsedSeconds = 0.0
        }
    }

    // Release camera when the composable leaves composition
    DisposableEffect(Unit) {
        onDispose { cameraCapture.unbind() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Header (Teal app name in DM Serif Display)
            Text(
                text = "PulseView",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            // Camera Preview Area with 4dp rounded corners
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }.also { previewView ->
                            cameraCapture.bindCamera(previewView)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Recording Status Chip (top-right badge, visible only during recording)
                if (isRecording) {
                    RecordingStatusChip(
                        frameCount = frameCount,
                        elapsedSeconds = elapsedSeconds,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                    )
                }

                // Progress Bar towards 25s fixed recording duration
                if (isRecording) {
                    LinearProgressIndicator(
                        progress = { (elapsedSeconds / FIXED_RECORDING_DURATION_SECONDS).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Instructional Text (only shown when not recording and not processing)
            if (!isRecording && !isProcessing) {
                Text(
                    text = "Position your face in the frame and stay still",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )
            } else {
                // Invisible spacing to prevent layout height shifts during recording
                Spacer(Modifier.height(32.dp))
            }

            Spacer(Modifier.height(16.dp))

            // Record FAB (disabled during recording or processing)
            RecordButton(
                isRecording = isRecording,
                enabled = !isRecording && !isProcessing,
                onClick = {
                    if (!isRecording && !isProcessing) {
                        // ── START RECORDING ──────────────────────────────────────
                        statusText = null
                        isRecording = true
                        recordingStartTimeNs = System.nanoTime()
                        cameraCapture.startRecording()
                    }
                }
            )

            // Post-recording status/debug message
            statusText?.let { msg ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            Spacer(Modifier.height(16.dp))
        }

        // ── Processing Overlay ──────────────────────────────────────────────
        if (isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 4.dp,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "Analyzing heart rate…",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.textPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Running face detection, preprocessing,\nand neural network inference",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.textMuted,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }


    }
}

/**
 * Run the full rPPG inference pipeline on a background thread.
 *
 * Pipeline: frames → Preprocessor → OnnxInference (per chunk) → concatenate BVP → FFT HR
 *
 * @return [InferenceResult] on success, or `null` on failure.
 */
private suspend fun runInferencePipeline(
    context: Context,
    frames: List<Mat>,
    numFrames: Int,
    recordingDurationSeconds: Double,
    achievedFps: Double
): InferenceResult? = withContext(Dispatchers.Default) {
    try {
        Log.i(TAG, "=== INFERENCE PIPELINE START ===")
        Log.i(TAG, "Frames: $numFrames, Duration: %.1fs, FPS: %.1f".format(recordingDurationSeconds, achievedFps))

        // ── 1. Load Haar cascade and create Preprocessor ─────────────────
        val cascadeFile = copyCascadeToFilesDir(context)
        val cascade = CascadeClassifier(cascadeFile.absolutePath)
        check(!cascade.empty()) {
            "CascadeClassifier is empty after loading from ${cascadeFile.absolutePath}"
        }
        Log.i(TAG, "Cascade loaded: ${cascadeFile.absolutePath}")

        val preprocessor = Preprocessor(cascade)

        // ── 2. Preprocess: face detect → crop → diff-normalize → chunk ──
        Log.i(TAG, "Running preprocessRawCrops on $numFrames frames...")
        val (chunks, rawCrops) = preprocessor.preprocessRawCrops(frames)
        Log.i(TAG, "Preprocessing complete: ${chunks.size} chunks produced, ${rawCrops.size} raw crops")

        // Release captured frames — they're no longer needed after preprocessing
        frames.forEach { it.release() }
        Log.i(TAG, "Released $numFrames Mat frames")

        if (chunks.isEmpty()) {
            Log.e(TAG, "No chunks produced — need at least ${Preprocessor.FRAME_DEPTH} frames for one chunk")
            return@withContext null
        }

        // ── 3. ONNX inference: run model on each chunk ──────────────────
        Log.i(TAG, "Running ONNX inference on ${chunks.size} chunks...")
        val onnx = OnnxInference(context)
        val allBvp = mutableListOf<Float>()

        for ((idx, chunk) in chunks.withIndex()) {
            val (flatNCHW, frameCount) = chunk
            val bvp = onnx.runInference(flatNCHW, frameCount)
            Log.i(TAG, "Chunk $idx: input ${frameCount} frames → ${bvp.size} BVP values")
            allBvp.addAll(bvp.toList())
        }

        onnx.close()
        Log.i(TAG, "ONNX inference complete: ${allBvp.size} total BVP values")

        if (allBvp.isEmpty()) {
            Log.e(TAG, "No BVP values produced from inference")
            return@withContext null
        }

        // ── 4. POS heart rate calculation ────────────────────────────────
        Log.i(TAG, "Running POS heart rate calculation...")
        val posResult = PosHrCalculator.computePosHr(
            rawCrops,
            srcSize = Preprocessor.IMG_SIZE,
            dstSize = 36,
            chunkDepth = 160,
            stride = 30,
            fs = achievedFps
        )
        val posHr = posResult.posHr
        val posHrStr = if (posHr != null) "%.2f BPM".format(posHr) else "N/A"
        val posChunksSize = posResult.totalChunks
        val validPosChunksCount = posResult.validChunks
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "POS Heart Rate: $posHrStr (Valid chunks: $validPosChunksCount/$posChunksSize)")
        }

        // ── 5. FFT heart rate calculation ────────────────────────────────
        val bvpArray = allBvp.toFloatArray()
        Log.i(TAG, "Computing raw/corrected HR from ${bvpArray.size} BVP samples via FFT...")
        val rawHrBpm = FftHrCalculator.computeHrRaw(bvpArray, fs = achievedFps)
        val correctedHrBpm = FftHrCalculator.computeHr(bvpArray, fs = achievedFps, posHrHint = posHr)

        val isFallback = posHr != null && kotlin.math.abs(correctedHrBpm - posHr) < 1e-9

        val divergence = if (posHr != null) {
            if (isFallback) {
                kotlin.math.abs(rawHrBpm - posHr)
            } else {
                kotlin.math.abs(correctedHrBpm - posHr)
            }
        } else {
            null
        }
        val divergenceStr = if (divergence != null) "%.2f BPM".format(divergence) else "N/A"

        val isReliable = when {
            posHr == null -> false
            isFallback -> null // Limited Signal (fallback to POS)
            else -> divergence!! <= 10.0
        }
        val reliableStr = when (isReliable) {
            true -> "YES"
            false -> "NO"
            null -> "LIMITED"
        }

        val correctionSource = when {
            posHr == null -> "NO_POS_GUIDE"
            isFallback -> "POS_FALLBACK"
            else -> "FFT_PEAK_MATCH"
        }

        val wasFallback = rawCrops.size < 160
        val posModeStr = if (wasFallback) "Fallback (Single Window)" else "Standard (Sliding Windows)"
        val firstChunkDepth = posResult.firstChunkDepth

        if (BuildConfig.DEBUG) {
            Log.i(TAG, """
                === PIPELINE DIAGNOSTICS ===
                Frames Received:  $numFrames
                POS Mode:         $posModeStr
                POS Chunk Depth:  $firstChunkDepth frames
                POS Total Chunks: $posChunksSize
                POS Valid Chunks: $validPosChunksCount
                Raw FFT HR:       %.2f BPM
                POS HR:          $posHrStr
                Corrected FFT HR: %.2f BPM
                Correction Src:   $correctionSource
                Divergence:      $divergenceStr
                Signal Reliable: $reliableStr
                ============================
            """.trimIndent().format(rawHrBpm, correctedHrBpm))
        }

        // ── 5b. HRV feature extraction ───────────────────────────────────
        Log.i(TAG, "Extracting HRV features...")
        val hrvFeatures = BvpFeatureExtractor.extractFeatures(bvpArray, achievedFps)
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "HRV: ibiMean=${hrvFeatures.ibiMeanMs}, ibiStd=${hrvFeatures.ibiStdMs}, rmssd=${hrvFeatures.rmssd}, sdnn=${hrvFeatures.sdnn}, pnn50=${hrvFeatures.pnn50}, apgRatio=${hrvFeatures.apgBaRatio}")
        }

        // ── 6. Sanity check ──────────────────────────────────────────────
        if (correctedHrBpm < 30.0 || correctedHrBpm > 200.0) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "WARNING: Corrected HR %.2f BPM is outside physiological range [30-200]!".format(correctedHrBpm))
            }
        }

        Log.i(TAG, "=== INFERENCE PIPELINE COMPLETE ===")

        InferenceResult(
            heartRateBpm = correctedHrBpm,
            heartRateBpmRaw = rawHrBpm,
            posHeartRateBpm = posHr,
            signalReliable = isReliable,
            recordingDurationSeconds = recordingDurationSeconds,
            frameCount = numFrames,
            achievedFps = achievedFps,
            bvpSignal = bvpArray,
            ibiMeanMs = hrvFeatures.ibiMeanMs,
            ibiStdMs = hrvFeatures.ibiStdMs,
            rmssd = hrvFeatures.rmssd,
            sdnn = hrvFeatures.sdnn,
            pnn50 = hrvFeatures.pnn50,
            apgBaRatio = hrvFeatures.apgBaRatio
        )
    } catch (e: Exception) {
        Log.e(TAG, "Inference pipeline failed: ${e.message}", e)
        // Still release frames on error
        try { frames.forEach { it.release() } } catch (_: Exception) {}
        null
    }
}

/**
 * Copy the Haar cascade XML from res/raw to filesDir.
 *
 * CascadeClassifier requires a real filesystem path (not an InputStream).
 * Pattern borrowed from PreprocessorVideoValidationTest.
 */
private fun copyCascadeToFilesDir(context: Context): File {
    val outFile = File(context.filesDir, "haarcascade_frontalface_default.xml")
    if (outFile.exists()) return outFile

    val rawId = R.raw.haarcascade_frontalface_default

    context.resources.openRawResource(rawId).use { input ->
        FileOutputStream(outFile).use { output ->
            input.copyTo(output)
        }
    }
    Log.i(TAG, "Cascade copied to: ${outFile.absolutePath} (${outFile.length()} bytes)")
    return outFile
}

/**
 * Pulsing "REC" status chip shown during recording.
 * Styled with persistent dark background, Plex Mono font, and 4dp border radius.
 */
@Composable
private fun RecordingStatusChip(
    frameCount: Int,
    elapsedSeconds: Double,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rec_blink")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 0.2f,
        animationSpec = infiniteRepeatable(
            animation  = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_alpha"
    )

    val secondsInt = elapsedSeconds.toInt()
    val text = "REC  $frameCount F  ${secondsInt}s / 25s"
    val contentColor = MaterialTheme.colorScheme.textPrimary

    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.7f),
                shape = RoundedCornerShape(4.dp)
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.border,
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = MaterialTheme.colorScheme.error.copy(alpha = dotAlpha),
                        shape = CircleShape
                    )
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = text,
                color = contentColor,
                style = MonoNumericStyle,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 0.5.sp
            )
        }
    }
}

/**
 * Record / Stop action button.
 * Uses 4dp border radius to match web design system.
 */
@Composable
private fun RecordButton(isRecording: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val targetColor = if (isRecording) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val buttonColor by animateColorAsState(
        targetValue = targetColor,
        label = "fab_color"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "fab_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = if (isRecording) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fab_scale"
    )

    FloatingActionButton(
        onClick = onClick,
        containerColor = if (enabled) buttonColor else MaterialTheme.colorScheme.textMuted,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(4.dp), // Override to 4dp
        modifier = Modifier
            .size(72.dp)
            .scale(scale)
    ) {
        // Use text icons to avoid needing an icon pack dependency
        Text(
            text = if (isRecording) "■" else "●",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
            fontFamily = IbmPlexSans
        )
    }
}

/**
 * Shown when camera permission was denied.
 */
@Composable
fun PermissionDeniedScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "📷",
                fontSize = 64.sp
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Camera permission required",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Please restart the app and grant camera access.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }
    }
}

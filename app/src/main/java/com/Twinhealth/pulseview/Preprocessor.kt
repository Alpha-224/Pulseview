package com.Twinhealth.pulseview

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfRect
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.CascadeClassifier
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Preprocessor for the EfficientPhys rPPG model.
 *
 * Replicates the exact preprocessing pipeline from the validated Python reference
 * implementation, ported to Kotlin using OpenCV Android. This is a pure
 * data-processing class with no UI dependencies.
 *
 * Pipeline: face detection → crop & resize → diff-normalize → chunk → HWC-to-NCHW.
 *
 * @param faceCascade A loaded [CascadeClassifier] (e.g., haarcascade_frontalface_default)
 *                    used for face detection on the first frame.
 */
class Preprocessor(private val faceCascade: CascadeClassifier) {

    companion object {
        /** Target spatial resolution for model input. */
        const val IMG_SIZE = 72

        /** Number of "real" frames per inference chunk (model outputs FRAME_DEPTH BVP values). */
        const val FRAME_DEPTH = 20

        /** Factor by which the detected face bounding box is expanded. */
        const val LARGE_BOX_COEF = 1.5

        // ──────────────────────────────────────────────────────────────────
        // Pure-math functions live here so they can be tested on JVM
        // without loading OpenCV native libraries.
        // ──────────────────────────────────────────────────────────────────

        /**
         * Compute inter-frame normalized differences and standardize globally.
         *
         * This is a faithful port of the Python reference:
         * ```python
         * diff[j] = (data[j+1] - data[j]) / (data[j+1] + data[j] + 1e-7)
         * diff    = diff / np.std(diff)
         * diff[np.isnan(diff)] = 0
         * ```
         *
         * The result is then **padded** back to the original length `n` by duplicating
         * the last diff frame (not zero-padding), so `output.size == input.size`.
         *
         * @param frames Array of HWC `FloatArray`s (length `n`), each of size `IMG_SIZE * IMG_SIZE * 3`.
         * @return Array of diff-normalized, padded `FloatArray`s (length `n`).
         */
        fun diffNormalize(frames: Array<FloatArray>): Array<FloatArray> {
            val n = frames.size
            require(n >= 2) { "Need at least 2 frames to compute differences, got $n" }

            val diffLen = n - 1
            val elemCount = frames[0].size // 72 * 72 * 3

            // ── Step 1: element-wise normalized differences ──────────────
            val diff = Array(diffLen) { j ->
                val curr = frames[j]
                val next = frames[j + 1]
                FloatArray(elemCount) { k ->
                    (next[k] - curr[k]) / (next[k] + curr[k] + 1e-7f)
                }
            }

            // ── Step 2: compute global std across ALL diff elements ──────
            val totalElements = diffLen.toLong() * elemCount
            var sum = 0.0
            var sumSq = 0.0
            for (frame in diff) {
                for (v in frame) {
                    sum += v
                    sumSq += v.toDouble() * v.toDouble()
                }
            }
            val mean = sum / totalElements
            val variance = (sumSq / totalElements) - (mean * mean)
            val std = sqrt(variance).toFloat()

            // Guard against divide-by-zero (mirrors np.std behavior).
            val safeDivisor = if (std < 1e-10f) 1e-10f else std

            // ── Step 3: divide by global std & replace NaN with 0 ────────
            for (frame in diff) {
                for (k in frame.indices) {
                    val v = frame[k] / safeDivisor
                    frame[k] = if (v.isNaN()) 0f else v
                }
            }

            // ── Step 4: pad to length n by duplicating the last diff frame
            val padded = Array(n) { j ->
                if (j < diffLen) {
                    diff[j]
                } else {
                    // Duplicate (copy) the last diff frame.
                    diff[diffLen - 1].copyOf()
                }
            }

            return padded
        }

        /**
         * Convert an array of HWC `FloatArray`s into a single **NCHW**-ordered flat array.
         *
         * This matches the tensor layout expected by PyTorch / ONNX Runtime:
         * `output[frameIdx * 3 * S * S  +  c * S * S  +  p] = input[frameIdx][p * 3 + c]`
         * where `S = imgSize` and `p` is the pixel index in row-major order.
         *
         * @param frames  Array of HWC `FloatArray`s, each of length `imgSize * imgSize * 3`.
         * @param imgSize Spatial dimension (default [IMG_SIZE] = 72).
         * @return A single flattened `FloatArray` of length `frames.size * 3 * imgSize * imgSize`.
         */
        fun toNCHWFlat(frames: Array<FloatArray>, imgSize: Int = IMG_SIZE): FloatArray {
            val spatialSize = imgSize * imgSize     // pixels per channel
            val channelCount = 3
            val frameStride = channelCount * spatialSize  // elements per frame in NCHW
            val output = FloatArray(frames.size * frameStride)

            for (f in frames.indices) {
                val hwc = frames[f]
                val frameOffset = f * frameStride
                for (p in 0 until spatialSize) {
                    val hwcBase = p * channelCount
                    for (c in 0 until channelCount) {
                        output[frameOffset + c * spatialSize + p] = hwc[hwcBase + c]
                    }
                }
            }
            return output
        }

        /**
         * Split diff-normalized frames into non-overlapping chunks, pad each chunk,
         * and convert to NCHW flat format.
         *
         * Each chunk contains [FRAME_DEPTH] (20) frames. The last frame of each
         * chunk is duplicated (→ 21 total) to match the model's training convention.
         * Any remainder frames that don't fill a full chunk are dropped.
         *
         * @param normalized Array of diff-normalized HWC `FloatArray`s.
         * @param imgSize Spatial dimension (default [IMG_SIZE] = 72).
         * @return A list of `(flatNCHW, 21)` pairs, one per chunk.
         */
        fun chunkAndConvert(normalized: Array<FloatArray>, imgSize: Int = IMG_SIZE): List<Pair<FloatArray, Int>> {
            val numChunks = normalized.size / FRAME_DEPTH
            val chunks = mutableListOf<Pair<FloatArray, Int>>()

            for (chunkIdx in 0 until numChunks) {
                val start = chunkIdx * FRAME_DEPTH
                val end = start + FRAME_DEPTH  // exclusive

                // Take FRAME_DEPTH frames + duplicate the last → 21 total.
                val chunkFrames = Array(FRAME_DEPTH + 1) { i ->
                    if (i < FRAME_DEPTH) {
                        normalized[start + i]
                    } else {
                        // Duplicate last frame of this chunk (copy to be safe).
                        normalized[end - 1].copyOf()
                    }
                }

                // Convert HWC → NCHW flat.
                val flat = toNCHWFlat(chunkFrames, imgSize)
                chunks.add(flat to (FRAME_DEPTH + 1))
            }

            return chunks
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 1. Face Detection
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Detect the largest face in [firstFrame] and return an expanded bounding box.
     *
     * The RGB frame is converted to grayscale for Haar cascade detection.
     * The largest face (by width) is selected and its bounding box is expanded
     * by [LARGE_BOX_COEF] (1.5×) around its center, then clamped to frame bounds.
     *
     * If no face is found, a [Rect] covering the entire frame is returned so
     * downstream processing can still proceed (albeit with a noisier signal).
     *
     * @param firstFrame The first video frame in RGB format.
     * @return A [Rect] representing the (possibly expanded) face region.
     */
    fun detectFaceBox(firstFrame: Mat): Rect {
        // Convert RGB → grayscale for the cascade classifier.
        val gray = Mat()
        Imgproc.cvtColor(firstFrame, gray, Imgproc.COLOR_RGB2GRAY)

        val faces = MatOfRect()
        faceCascade.detectMultiScale(
            gray,
            faces,
            1.1,            // scaleFactor
            5,              // minNeighbors
            0,              // flags (unused in OpenCV 4.x, pass 0)
            Size(30.0, 30.0) // minSize
        )
        gray.release()

        val faceArray = faces.toArray()
        faces.release()

        if (faceArray.isEmpty()) {
            // Fallback: use the entire frame.
            return Rect(0, 0, firstFrame.cols(), firstFrame.rows())
        }

        // Pick the face with the largest width.
        val largest = faceArray.maxByOrNull { it.width }!!

        // Expand by LARGE_BOX_COEF (1.5×) keeping the center fixed.
        // new_x = x - 0.25 * w,  new_w = 1.5 * w  (and similarly for y/h).
        val expandedX = (largest.x - 0.25 * largest.width).toInt()
        val expandedY = (largest.y - 0.25 * largest.height).toInt()
        val expandedW = (LARGE_BOX_COEF * largest.width).toInt()
        val expandedH = (LARGE_BOX_COEF * largest.height).toInt()

        // Clamp to frame boundaries.
        val clampedX = max(0, expandedX)
        val clampedY = max(0, expandedY)
        val clampedW = min(expandedW, firstFrame.cols() - clampedX)
        val clampedH = min(expandedH, firstFrame.rows() - clampedY)

        return Rect(clampedX, clampedY, clampedW, clampedH)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. Crop & Resize
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Crop each frame to [faceBox], resize to [IMG_SIZE]×[IMG_SIZE], and normalize to \[0, 1\].
     *
     * Each resulting frame is flattened into a [FloatArray] of length
     * `IMG_SIZE * IMG_SIZE * 3` in **HWC** order (OpenCV's native `Mat` layout).
     *
     * Intermediate `Mat` objects are released immediately to keep memory bounded
     * when processing hundreds of frames.
     *
     * @param frames   List of RGB `Mat` frames (all assumed same dimensions).
     * @param faceBox  The face region to crop to (from [detectFaceBox]).
     * @return An array of HWC-ordered `FloatArray`s, one per input frame.
     */
    fun cropAndResize(frames: List<Mat>, faceBox: Rect): Array<FloatArray> {
        val targetSize = Size(IMG_SIZE.toDouble(), IMG_SIZE.toDouble())
        val pixelCount = IMG_SIZE * IMG_SIZE * 3

        return Array(frames.size) { i ->
            // Crop — Mat.submat returns a *view*, so we must clone or resize into a new Mat.
            val cropped = Mat(frames[i], faceBox)

            // Resize to 72×72 using INTER_AREA (best for down-sampling).
            val resized = Mat()
            Imgproc.resize(cropped, resized, targetSize, 0.0, 0.0, Imgproc.INTER_AREA)
            cropped.release()

            // Convert to 32-bit float and normalize to [0, 1].
            val floatMat = Mat()
            resized.convertTo(floatMat, CvType.CV_32FC3, 1.0 / 255.0)
            resized.release()

            // Extract pixel data in HWC order (OpenCV default).
            val buffer = FloatArray(pixelCount)
            floatMat.get(0, 0, buffer)
            floatMat.release()

            buffer
        }
    }



    // ──────────────────────────────────────────────────────────────────────────
    // 3. Full Pipeline (instance method — requires OpenCV)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * End-to-end preprocessing: face detect → crop/resize → diff-normalize → chunk → NCHW.
     *
     * The diff-normalized frames are split into non-overlapping chunks of
     * [FRAME_DEPTH] (20) frames. Any remainder that doesn't fill a full chunk
     * is dropped. Each chunk's last frame is duplicated (total 21 frames) to
     * match the model's training convention—the ONNX model performs an internal
     * diff and expects `N + 1` input frames to produce `N` BVP outputs.
     *
     * @param frames List of RGB `Mat` frames from the camera/video.
     * @return A list of `(flatNCHW, 21)` pairs, one per chunk, ready for
     *         [OnnxInference.runInference].
     */
    fun preprocessToChunks(frames: List<Mat>): List<Pair<FloatArray, Int>> {
        require(frames.isNotEmpty()) { "Frame list must not be empty" }

        // 1. Detect face on the first frame.
        val faceBox = detectFaceBox(frames[0])

        // 2. Crop & resize all frames.
        val cropped = cropAndResize(frames, faceBox)

        // 3. Diff-normalize (returns array of length == frames.size).
        val normalized = Companion.diffNormalize(cropped)

        // 4. Chunk, pad, and convert to NCHW.
        return Companion.chunkAndConvert(normalized)
    }

    /**
     * Preprocesses frames for both ONNX (diff-normalized NCHW chunks) and POS (raw crops).
     *
     * Detects the face box once, crops/resizes all frames to raw HWC [0, 1] arrays,
     * and returns both the diff-normalized NCHW chunks and the raw crops.
     *
     * @param frames List of RGB Mat frames.
     * @return Pair of (diffNormalizedNCHWChunks, rawHWCCrops).
     */
    fun preprocessRawCrops(frames: List<Mat>): Pair<List<Pair<FloatArray, Int>>, List<FloatArray>> {
        require(frames.isNotEmpty()) { "Frame list must not be empty" }

        // 1. Detect face on the first frame.
        val faceBox = detectFaceBox(frames[0])

        // 2. Crop & resize all frames.
        val cropped = cropAndResize(frames, faceBox)

        // 3. Diff-normalize
        val normalized = Companion.diffNormalize(cropped)

        // 4. Chunk and convert to NCHW for ONNX.
        val onnxChunks = Companion.chunkAndConvert(normalized)

        return Pair(onnxChunks, cropped.toList())
    }
}

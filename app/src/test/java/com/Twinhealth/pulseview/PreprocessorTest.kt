package com.Twinhealth.pulseview

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure-math functions in [Preprocessor].
 *
 * These tests validate [Preprocessor.diffNormalize], [Preprocessor.toNCHWFlat],
 * and [Preprocessor.chunkAndConvert] in isolation — all three live in
 * `Preprocessor.Companion` and have zero OpenCV/Android dependencies, so they
 * run on a standard JVM without an emulator.
 *
 * Why these tests matter:
 *  - **diffNormalize** must exactly match NumPy's diff-normalize math
 *    (element-wise normalized difference → global std division → NaN cleanup
 *    → padding). Even a tiny deviation (e.g., per-frame std instead of global,
 *    or wrong epsilon, or zero-padding instead of last-frame duplication) will
 *    produce incorrect BVP signals.
 *  - **toNCHWFlat** must produce the exact NCHW tensor layout that ONNX Runtime
 *    expects. An HWC↔NCHW mixup would silently feed garbled data to the model,
 *    producing garbage heart-rate estimates with no obvious error.
 *  - **chunkAndConvert** must split frames into the right chunk boundaries and
 *    duplicate each chunk's *own* last frame (not a global one), matching the
 *    model's training-time data loading convention.
 */
class PreprocessorTest {

    // ═══════════════════════════════════════════════════════════════════════
    // Test 1: diffNormalize vs. hand-computed reference
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Verifies that [Preprocessor.diffNormalize] produces numerically identical
     * output to a deliberately naïve reference implementation that mirrors the
     * Python code line-for-line.
     *
     * The input is a simple ramp so the math is fully predictable: 21 frames,
     * each with 4 elements (not realistic image data, but sufficient to
     * exercise every code path — inter-frame diff, global std, NaN guard,
     * and padding).
     */
    @Test
    fun diffNormalize_matchesReference() {
        // ── Synthetic ramp input: 21 frames × 4 elements each ───────────
        val testFrames = Array(21) { i ->
            FloatArray(4) { k -> (i * 0.1f + k * 0.01f) }
        }

        // ── Reference implementation (deliberately separate & un-optimized) ─
        val expected = referenceDiffNormalize(testFrames.map { it.copyOf() }.toTypedArray())

        // ── System under test ───────────────────────────────────────────────
        val actual = Preprocessor.diffNormalize(testFrames)

        // ── Assert output length matches input (padding restored to 21) ─────
        assertEquals(
            "diffNormalize must pad output back to input length",
            testFrames.size,
            actual.size
        )

        // ── Assert every element matches within floating-point tolerance ─────
        val tolerance = 1e-5f
        for (j in expected.indices) {
            for (k in expected[j].indices) {
                assertEquals(
                    "Mismatch at frame=$j, element=$k: " +
                            "expected=${expected[j][k]}, actual=${actual[j][k]}",
                    expected[j][k],
                    actual[j][k],
                    tolerance
                )
            }
        }
    }

    /**
     * Reference implementation of diff-normalize, deliberately written as a
     * straightforward transliteration of the Python code so it serves as an
     * independent oracle.
     *
     * ```python
     * diff[j] = (data[j+1] - data[j]) / (data[j+1] + data[j] + 1e-7)
     * diff    = diff / np.std(diff)       # population std (ddof=0)
     * diff[np.isnan(diff)] = 0
     * # then pad back to length n by duplicating last diff frame
     * ```
     */
    private fun referenceDiffNormalize(frames: Array<FloatArray>): Array<FloatArray> {
        val n = frames.size
        val len = frames[0].size
        val diff = Array(n - 1) { FloatArray(len) }

        // Step 1: element-wise normalized difference.
        for (j in 0 until n - 1) {
            for (k in 0 until len) {
                val a = frames[j + 1][k]
                val b = frames[j][k]
                diff[j][k] = (a - b) / (a + b + 1e-7f)
            }
        }

        // Step 2: population std (ddof=0), matching NumPy's default.
        val flat = diff.flatMap { it.toList() }
        val mean = flat.average()
        val variance = flat.map { (it - mean) * (it - mean) }.average()
        val std = kotlin.math.sqrt(variance).toFloat().coerceAtLeast(1e-10f)

        // Step 3: divide by global std, replace NaN with 0.
        for (j in 0 until n - 1) {
            for (k in 0 until len) {
                var v = diff[j][k] / std
                if (v.isNaN()) v = 0f
                diff[j][k] = v
            }
        }

        // Step 4: pad back to length n (duplicate last diff frame).
        return Array(n) { i -> if (i < n - 1) diff[i] else diff[n - 2].copyOf() }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test 2: toNCHWFlat layout correctness
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Verifies that [Preprocessor.toNCHWFlat] correctly converts HWC → NCHW.
     *
     * Uses a 2×2 image (4 pixels, 3 channels → HWC length 12) with two frames
     * whose values are chosen to be distinguishable so any transposition bug
     * would produce a visibly wrong result.
     *
     * HWC layout per frame (pixel-major):
     *   pixel0: R, G, B
     *   pixel1: R, G, B
     *   pixel2: R, G, B
     *   pixel3: R, G, B
     *
     * NCHW layout per frame (channel-major):
     *   channel R: pixel0, pixel1, pixel2, pixel3
     *   channel G: pixel0, pixel1, pixel2, pixel3
     *   channel B: pixel0, pixel1, pixel2, pixel3
     */
    @Test
    fun toNCHWFlat_correctLayout() {
        val imgSize = 2  // 2×2 = 4 pixels

        // Frame 0:
        //   pixel0 = (R=1, G=5, B=9)
        //   pixel1 = (R=2, G=6, B=10)
        //   pixel2 = (R=3, G=7, B=11)
        //   pixel3 = (R=4, G=8, B=12)
        val frame0HWC = floatArrayOf(
            1f, 5f, 9f,     // pixel 0: R, G, B
            2f, 6f, 10f,    // pixel 1
            3f, 7f, 11f,    // pixel 2
            4f, 8f, 12f     // pixel 3
        )

        // Frame 1: offset by 100 to make values distinguishable.
        //   pixel0 = (R=101, G=105, B=109)
        //   pixel1 = (R=102, G=106, B=110)
        //   pixel2 = (R=103, G=107, B=111)
        //   pixel3 = (R=104, G=108, B=112)
        val frame1HWC = floatArrayOf(
            101f, 105f, 109f,
            102f, 106f, 110f,
            103f, 107f, 111f,
            104f, 108f, 112f
        )

        // Hand-computed expected NCHW output:
        // Frame 0 NCHW:  R=[1,2,3,4], G=[5,6,7,8], B=[9,10,11,12]
        // Frame 1 NCHW:  R=[101,102,103,104], G=[105,106,107,108], B=[109,110,111,112]
        val expected = floatArrayOf(
            // Frame 0
            1f, 2f, 3f, 4f,         // channel R
            5f, 6f, 7f, 8f,         // channel G
            9f, 10f, 11f, 12f,      // channel B
            // Frame 1
            101f, 102f, 103f, 104f, // channel R
            105f, 106f, 107f, 108f, // channel G
            109f, 110f, 111f, 112f  // channel B
        )

        val actual = Preprocessor.toNCHWFlat(arrayOf(frame0HWC, frame1HWC), imgSize = imgSize)

        // Verify total length: 2 frames × 3 channels × 4 pixels = 24
        assertEquals(
            "NCHW output length should be frames * channels * pixels",
            expected.size,
            actual.size
        )

        // Verify element-by-element exact match (values are integers cast to float, no rounding).
        for (i in expected.indices) {
            assertEquals(
                "Mismatch at NCHW index $i: expected=${expected[i]}, actual=${actual[i]}",
                expected[i],
                actual[i],
                0f  // exact match for integer values
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test 3: chunkAndConvert boundary behavior
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Verifies chunk boundary behavior for a 41-frame input:
     *
     * 1. Exactly 2 chunks are produced (41 / 20 = 2 full chunks, 1 remainder dropped).
     * 2. Each chunk has 21 frames (FRAME_DEPTH + 1 duplicate).
     * 3. Each chunk's last frame is a duplicate of its *own* last real frame,
     *    NOT the global last frame — this confirms per-chunk padding is local.
     *
     * We use small 4-element frames to keep the test lightweight while still
     * exercising all the chunking, padding, and NCHW conversion logic.
     */
    @Test
    fun chunkAndConvert_correctBoundaries() {
        val elemSize = 4  // 4 elements per frame (pretend 1×1 image, but really just testing chunking)
        val frameDepth = Preprocessor.FRAME_DEPTH  // 20

        // Create 41 distinguishable frames: frame i has all elements set to (i + 1).
        // This makes it trivial to verify which frame ended up where.
        val input = Array(41) { i ->
            FloatArray(elemSize) { (i + 1).toFloat() }
        }

        // imgSize for toNCHWFlat: since elemSize = 4 = 1*1*3... no wait, 4 ≠ S*S*3 for
        // any integer S. We need elemSize to be divisible by 3 and a perfect square
        // when divided by 3, so toNCHWFlat's indexing works correctly.
        // Use elemSize = 12 → imgSize = 2 (2*2*3 = 12).
        val imgSize = 2
        val adjustedInput = Array(41) { i ->
            FloatArray(imgSize * imgSize * 3) { (i + 1).toFloat() }
        }

        val chunks = Preprocessor.chunkAndConvert(adjustedInput, imgSize = imgSize)

        // ── Assert exactly 2 chunks (41 / 20 = 2, dropping 1 remainder) ─────
        assertEquals("41 frames should produce exactly 2 chunks", 2, chunks.size)

        // ── Assert each chunk pair declares 21 frames ───────────────────────
        for ((idx, chunk) in chunks.withIndex()) {
            assertEquals(
                "Chunk $idx should report FRAME_DEPTH + 1 = 21 frames",
                frameDepth + 1,
                chunk.second
            )
        }

        // ── Verify expected flat output length per chunk ─────────────────────
        val expectedChunkLen = (frameDepth + 1) * 3 * imgSize * imgSize
        for ((idx, chunk) in chunks.withIndex()) {
            assertEquals(
                "Chunk $idx flat array should have ${expectedChunkLen} elements",
                expectedChunkLen,
                chunk.first.size
            )
        }

        // ── Verify per-chunk local padding (not global) ─────────────────────
        // Chunk 0 uses frames 0..19  → last real frame has value 20, duplicate at index 20 also has value 20
        // Chunk 1 uses frames 20..39 → last real frame has value 40, duplicate at index 20 also has value 40
        //
        // We decode from NCHW back to check the last frame of each chunk.
        // In NCHW: frame f's channel c at pixel p is at offset f*3*S*S + c*S*S + p.
        // All pixels in our test data have the same value (frame index + 1),
        // so we just check pixel 0, channel 0 of the last frame in each chunk.
        val spatialSize = imgSize * imgSize
        val frameStride = 3 * spatialSize

        // Chunk 0: last frame (index 20 in the 21-frame chunk)
        val chunk0 = chunks[0].first
        val chunk0LastFramePixel0R = chunk0[20 * frameStride + 0 * spatialSize + 0]
        // Frame index 19 (0-based) in original input → value 20.0
        assertEquals(
            "Chunk 0's duplicated last frame should match frame index 19 (value=20)",
            20f,
            chunk0LastFramePixel0R,
            0f
        )

        // Chunk 1: last frame (index 20 in the 21-frame chunk)
        val chunk1 = chunks[1].first
        val chunk1LastFramePixel0R = chunk1[20 * frameStride + 0 * spatialSize + 0]
        // Frame index 39 (0-based) in original input → value 40.0
        assertEquals(
            "Chunk 1's duplicated last frame should match frame index 39 (value=40)",
            40f,
            chunk1LastFramePixel0R,
            0f
        )

        // Confirm the two chunks' last frames are NOT the same value
        // (i.e., each chunk duplicates its own local last frame, not a global one).
        assertTrue(
            "Chunk 0 and Chunk 1 should have different last-frame values " +
                    "(local duplication, not global). " +
                    "Chunk0=${chunk0LastFramePixel0R}, Chunk1=${chunk1LastFramePixel0R}",
            chunk0LastFramePixel0R != chunk1LastFramePixel0R
        )
    }
}

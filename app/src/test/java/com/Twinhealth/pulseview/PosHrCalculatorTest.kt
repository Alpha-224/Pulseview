package com.Twinhealth.pulseview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * JVM unit tests for [PosHrCalculator].
 *
 * Validates the spatial RGB averaging, sliding window POS projection, zero-phase
 * IIR filtering, and final FFT peak heart rate estimation against Python references.
 */
class PosHrCalculatorTest {

    @Test
    fun testPosHrCalculator_1_2Hz_matchesPythonReference() {
        val fs = 30.0
        val nFrames = 160
        val pulseHz = 1.2
        val h = 36
        val w = 36
        val spatialSize = h * w
        val totalElements = nFrames * 3 * spatialSize

        // 1. Reconstruct raw clip in DCHW (Array of FloatArrays of size 3*H*W)
        val clip = Array(nFrames) { d ->
            val t = d / fs
            val rVal = 0.8 * sin(2.0 * PI * pulseHz * t) + 2.0
            val gVal = 1.0 * sin(2.0 * PI * pulseHz * t) + 2.0
            val bVal = 0.6 * sin(2.0 * PI * pulseHz * t) + 2.0

            FloatArray(3 * spatialSize) { idx ->
                val c = idx / spatialSize
                when (c) {
                    0 -> rVal.toFloat()
                    1 -> gVal.toFloat()
                    else -> bVal.toFloat()
                }
            }
        }

        // 2. Standardize (z-score) globally: (x - mean) / (std + 1e-7)
        var sum = 0.0
        var sumSq = 0.0
        for (frame in clip) {
            for (v in frame) {
                sum += v
                sumSq += v.toDouble() * v
            }
        }
        val mean = sum / totalElements
        val variance = sumSq / totalElements - mean * mean
        val std = sqrt(variance)
        val safeDivisor = std + 1e-7

        for (frame in clip) {
            for (i in frame.indices) {
                frame[i] = ((frame[i] - mean) / safeDivisor).toFloat()
            }
        }

        // 3. Compute Spatial Means
        val rgbMean = PosHrCalculator.computeSpatialMeans(clip)
        assertEquals(nFrames, rgbMean.size)

        // Python reference values from Section 5:
        // rgb_mean[0]  = [1.7235735016365155, 1.7235735016365155, 1.7235735016365155]
        // rgb_mean[80] = [3.0375381980089595, 3.3660293721020706, 2.7090470239158475]
        val expectedRgbMean0 = doubleArrayOf(1.7235735016365155, 1.7235735016365155, 1.7235735016365155)
        val expectedRgbMean80 = doubleArrayOf(3.0375381980089595, 3.3660293721020706, 2.7090470239158475)

        for (c in 0 until 3) {
            assertEquals("rgbMean[0][$c] mismatch", expectedRgbMean0[c], rgbMean[0][c], 0.001)
            assertEquals("rgbMean[80][$c] mismatch", expectedRgbMean80[c], rgbMean[80][c], 0.001)
        }

        // 4. POS sliding-window projection
        val hAcc = PosHrCalculator.posProjection(rgbMean, fs)
        assertEquals(nFrames, hAcc.size)

        // 5. Bandpass filtering with zi
        val (b, a) = PosHrCalculator.selectCoefficients(fs)
        val zi = PosHrCalculator.selectZi(fs)
        val filtered = PosHrCalculator.filtfilt(b, a, hAcc, zi)

        // Python reference values for filtered signal (first and last 10):
        val expectedFirst10 = doubleArrayOf(
            0.499197324060567, 1.107418473092495, 1.796302972534126, 2.616761471731158,
            3.5649086665007212, 4.573583906782271, 5.519007080719104, 6.241367866503115,
            6.5750679394015945, 6.382247983680285
        )
        val expectedLast10 = doubleArrayOf(
            1.261482497117202, 3.0710472502196966, 4.554251674247394, 5.605704651239402,
            6.163082956095712, 6.2102650067484095, 5.775121868512891, 4.9233566898789105,
            3.7500935562257594, 2.370633052653024
        )

        println("Actual first 10 filtered (1.2Hz): " + filtered.take(10).joinToString())
        println("Actual last 10 filtered (1.2Hz): " + filtered.takeLast(10).joinToString())

        for (i in 0 until 10) {
            assertEquals("filtered[$i] mismatch", expectedFirst10[i], filtered[i], 0.02)
            assertEquals("filtered[last-$i] mismatch", expectedLast10[9 - i], filtered[nFrames - 10 + (9 - i)], 0.02)
        }

        // 6. Compute Final POS HR
        val posHr = PosHrCalculator.posHrFromStandardizedClip(clip, fs)
        assertNotNull(posHr)
        assertEquals("POS HR output mismatch", 70.3125, posHr!!, 0.001)
    }

    @Test
    fun testPosHrCalculator_1_0Hz_matchesPythonReference() {
        val fs = 30.0
        val nFrames = 160
        val pulseHz = 1.0
        val h = 36
        val w = 36
        val spatialSize = h * w
        val totalElements = nFrames * 3 * spatialSize

        // 1. Reconstruct raw clip in DCHW (Array of FloatArrays of size 3*H*W)
        val clip = Array(nFrames) { d ->
            val t = d / fs
            val rVal = 0.8 * sin(2.0 * PI * pulseHz * t) + 2.0
            val gVal = 1.0 * sin(2.0 * PI * pulseHz * t) + 2.0
            val bVal = 0.6 * sin(2.0 * PI * pulseHz * t) + 2.0

            FloatArray(3 * spatialSize) { idx ->
                val c = idx / spatialSize
                when (c) {
                    0 -> rVal.toFloat()
                    1 -> gVal.toFloat()
                    else -> bVal.toFloat()
                }
            }
        }

        // 2. Standardize (z-score) globally: (x - mean) / (std + 1e-7)
        var sum = 0.0
        var sumSq = 0.0
        for (frame in clip) {
            for (v in frame) {
                sum += v
                sumSq += v.toDouble() * v
            }
        }
        val mean = sum / totalElements
        val variance = sumSq / totalElements - mean * mean
        val std = sqrt(variance)
        val safeDivisor = std + 1e-7

        for (frame in clip) {
            for (i in frame.indices) {
                frame[i] = ((frame[i] - mean) / safeDivisor).toFloat()
            }
        }

        // 3. Compute Spatial Means
        val rgbMean = PosHrCalculator.computeSpatialMeans(clip)
        assertEquals(nFrames, rgbMean.size)

        // Python reference values from Step 2 output:
        // rgb_mean[0]: [1.7185505114319461, 1.7185505114319461, 1.7185505114319461]
        // rgb_mean[80]: [0.521346072601345, 0.22204496289369494, 0.8206471823089955]
        val expectedRgbMean0 = doubleArrayOf(1.7185505114319461, 1.7185505114319461, 1.7185505114319461)
        val expectedRgbMean80 = doubleArrayOf(0.521346072601345, 0.22204496289369494, 0.8206471823089955)

        for (c in 0 until 3) {
            assertEquals("rgbMean[0][$c] mismatch", expectedRgbMean0[c], rgbMean[0][c], 0.001)
            assertEquals("rgbMean[80][$c] mismatch", expectedRgbMean80[c], rgbMean[80][c], 0.001)
        }

        // 4. POS sliding-window projection
        val hAcc = PosHrCalculator.posProjection(rgbMean, fs)
        assertEquals(nFrames, hAcc.size)

        // 5. Bandpass filtering with zi
        val (b, a) = PosHrCalculator.selectCoefficients(fs)
        val zi = PosHrCalculator.selectZi(fs)
        val filtered = PosHrCalculator.filtfilt(b, a, hAcc, zi)

        // Python reference values for filtered signal (first and last 10):
        val expectedFirst10 = doubleArrayOf(
            1.5985427479743743, 1.6297570918478048, 1.7077014078092323,
            1.8826341317688848, 2.193487218085278, 2.662465764973459, 3.2886815616860448,
            4.0421263254076925, 4.859993016217532, 5.6474182548158
        )
        val expectedLast10 = doubleArrayOf(
            4.6047672435690465, 6.495201775420817, 7.827586165881963, 8.589795860773258,
            8.812522413753001, 8.554369942780538, 7.886936221772667, 6.88341069199533,
            5.612932368383555, 4.141104828866101
        )

        println("Actual first 10 filtered (1.0Hz): " + filtered.take(10).joinToString())
        println("Actual last 10 filtered (1.0Hz): " + filtered.takeLast(10).joinToString())

        for (i in 0 until 10) {
            assertEquals("filtered[$i] mismatch", expectedFirst10[i], filtered[i], 0.02)
            assertEquals("filtered[last-$i] mismatch", expectedLast10[9 - i], filtered[nFrames - 10 + (9 - i)], 0.02)
        }

        // 6. Compute Final POS HR
        val posHr = PosHrCalculator.posHrFromStandardizedClip(clip, fs)
        assertNotNull(posHr)
        assertEquals("POS HR output mismatch", 63.28125, posHr!!, 0.001)
    }
}

package com.Twinhealth.pulseview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM unit test validating [BvpFeatureExtractor] against the python reference.
 */
class BvpFeatureExtractorTest {

    @Test
    fun testBvpFeatureExtractor_matchesPythonReference() {
        // Load the reference JSON file
        val file = File("/home/Adi/.gemini/antigravity-ide/scratch/bvp_validation_reference.json")
        assertTrue("Reference JSON file must exist", file.exists())

        val json = file.readText()

        // Parse lists from reference JSON
        val signal = parseDoubleArray(json, "signal")
        val fs = parseDouble(json, "fs") ?: 30.0
        val expectedDetrended = parseDoubleArray(json, "bvp_detrended")
        val expectedHr = parseDoubleArray(json, "bvp_hr")
        val expectedPeaks = parseIntArray(json, "detected_peaks")
        val expectedValidPeaks = parseIntArray(json, "valid_peaks")
        val expectedIbiMs = parseDoubleArray(json, "ibi_ms")

        // Parse features block
        val featuresBlock = extractFeaturesBlock(json)
        val expectedHrBpm = parseFeatureDouble(featuresBlock, "hr_bpm")
        val expectedIbiMeanMs = parseFeatureDouble(featuresBlock, "ibi_mean_ms")
        val expectedIbiStdMs = parseFeatureDouble(featuresBlock, "ibi_std_ms")
        val expectedRmssd = parseFeatureDouble(featuresBlock, "rmssd")
        val expectedSdnn = parseFeatureDouble(featuresBlock, "sdnn")
        val expectedPnn50 = parseFeatureDouble(featuresBlock, "pnn50")
        val expectedApgBaRatio = parseFeatureDouble(featuresBlock, "apg_ba_ratio")

        // 1. Verify linear detrending
        val actualDetrended = BvpFeatureExtractor.detrend(signal)
        assertEquals(expectedDetrended.size, actualDetrended.size)
        for (i in expectedDetrended.indices) {
            assertEquals("Detrended mismatch at $i", expectedDetrended[i], actualDetrended[i], 1e-9)
        }

        // 2. Verify bandpass filtering
        val (b, a) = PosHrCalculator.selectCoefficients(fs)
        val zi = PosHrCalculator.selectZi(fs)
        val actualHr = PosHrCalculator.filtfilt(b, a, actualDetrended, zi)
        assertEquals(expectedHr.size, actualHr.size)
        // Allow a small tolerance for differences in filtfilt implementation / padding / float arithmetic
        for (i in expectedHr.indices) {
            assertEquals("Filtered signal mismatch at $i", expectedHr[i], actualHr[i], 0.02)
        }

        // 3. Verify peak detection (raw find_peaks)
        val minDistance = (fs * 0.5).toInt()
        val meanHr = actualHr.average()
        val actualPeaks = BvpFeatureExtractor.findPeaks(
            actualHr,
            distance = minDistance,
            prominence = 0.6,
            height = meanHr,
            wlen = (fs * 10).toInt()
        )
        assertEquals("Raw peak count mismatch", expectedPeaks.size, actualPeaks.size)
        for (i in expectedPeaks.indices) {
            assertEquals("Raw peak index mismatch at $i", expectedPeaks[i], actualPeaks[i])
        }

        // 4. Verify BVP feature extraction pipeline
        // Convert signal back to FloatArray as expected by BvpFeatureExtractor
        val signalFloat = FloatArray(signal.size) { signal[it].toFloat() }
        val feats = BvpFeatureExtractor.extractFeaturesSingleChunk(signalFloat, fs)

        // Validate individual features
        assertNotNull(feats)

        // IBI Mean
        assertNotNull("ibiMeanMs should not be null", feats.ibiMeanMs)
        assertEquals("ibiMeanMs mismatch", expectedIbiMeanMs!!, feats.ibiMeanMs!!, 1e-3)

        // IBI Std / SDNN
        assertNotNull("ibiStdMs should not be null", feats.ibiStdMs)
        assertEquals("ibiStdMs mismatch", expectedIbiStdMs!!, feats.ibiStdMs!!, 1e-3)
        assertNotNull("sdnn should not be null", feats.sdnn)
        assertEquals("sdnn mismatch", expectedSdnn!!, feats.sdnn!!, 1e-3)

        // RMSSD
        assertNotNull("rmssd should not be null", feats.rmssd)
        assertEquals("rmssd mismatch", expectedRmssd!!, feats.rmssd!!, 1e-3)

        // pNN50
        assertNotNull("pnn50 should not be null", feats.pnn50)
        assertEquals("pnn50 mismatch", expectedPnn50!!, feats.pnn50!!, 1e-3)

        // APG b/a Ratio
        assertNotNull("apgBaRatio should not be null", feats.apgBaRatio)
        assertEquals("apgBaRatio mismatch", expectedApgBaRatio!!, feats.apgBaRatio!!, 1e-3)

        // ── 5. Verify Scale Invariance (Z-score Standardization Check) ─────────────────────
        // Today's bug showed that the raw (unstandardized) BVP output from efficientphys.onnx has 
        // a very small scale/amplitude (standard deviation ≈ 0.18 on real scans). 
        // Because find_peaks uses a fixed prominence threshold of 0.6 (calibrated for standard deviation = 1.0),
        // peak detection failed completely on the raw scale.
        // We resolve this by performing z-score standardization within extractFeatures.
        // Here we verify scale-invariance by scaling the input signal down by a factor of 10 (std ≈ 0.116).
        // If the extractor internally z-score standardizes the signal, the resulting features must be identical.
        // If z-score standardization is missing/reverted, peak detection will fail entirely (returning null features).
        val scaledSignalFloat = FloatArray(signal.size) { (signal[it] * 0.1).toFloat() }
        val scaledFeats = BvpFeatureExtractor.extractFeaturesSingleChunk(scaledSignalFloat, fs)

        assertNotNull("scaledFeats should not be null", scaledFeats)
        assertNotNull("scaled ibiMeanMs should not be null", scaledFeats.ibiMeanMs)
        assertEquals("scaled ibiMeanMs mismatch", expectedIbiMeanMs!!, scaledFeats.ibiMeanMs!!, 1e-3)
        assertEquals("scaled ibiStdMs mismatch", expectedIbiStdMs!!, scaledFeats.ibiStdMs!!, 1e-3)
        assertEquals("scaled rmssd mismatch", expectedRmssd!!, scaledFeats.rmssd!!, 1e-3)
        assertEquals("scaled sdnn mismatch", expectedSdnn!!, scaledFeats.sdnn!!, 1e-3)
        assertEquals("scaled pnn50 mismatch", expectedPnn50!!, scaledFeats.pnn50!!, 1e-3)
        assertEquals("scaled apgBaRatio mismatch", expectedApgBaRatio!!, scaledFeats.apgBaRatio!!, 1e-3)
    }

    @Test
    fun testBvpFeatureExtractor_matchesPythonChunkedReference() {
        val file = File("/home/Adi/.gemini/antigravity-ide/scratch/bvp_chunked_reference.json")
        assertTrue("Chunked Reference JSON file must exist", file.exists())

        val json = file.readText()

        val signal = parseDoubleArray(json, "signal")
        val fs = parseDouble(json, "fs") ?: 30.0

        val featuresBlock = extractFeaturesBlock(json)
        val expectedHrBpm = parseFeatureDouble(featuresBlock, "hr_bpm")
        val expectedIbiMeanMs = parseFeatureDouble(featuresBlock, "ibi_mean_ms")
        val expectedIbiStdMs = parseFeatureDouble(featuresBlock, "ibi_std_ms")
        val expectedRmssd = parseFeatureDouble(featuresBlock, "rmssd")
        val expectedSdnn = parseFeatureDouble(featuresBlock, "sdnn")
        val expectedPnn50 = parseFeatureDouble(featuresBlock, "pnn50")
        val expectedApgBaRatio = parseFeatureDouble(featuresBlock, "apg_ba_ratio")

        val signalFloat = FloatArray(signal.size) { signal[it].toFloat() }
        val feats = BvpFeatureExtractor.extractFeatures(signalFloat, fs)

        assertNotNull(feats)

        // IBI Mean
        if (expectedIbiMeanMs != null) {
            assertNotNull("ibiMeanMs should not be null", feats.ibiMeanMs)
            assertEquals("ibiMeanMs mismatch", expectedIbiMeanMs, feats.ibiMeanMs!!, 1e-3)
        }

        // IBI Std / SDNN
        if (expectedIbiStdMs != null) {
            assertNotNull("ibiStdMs should not be null", feats.ibiStdMs)
            assertEquals("ibiStdMs mismatch", expectedIbiStdMs, feats.ibiStdMs!!, 1e-3)
        }
        if (expectedSdnn != null) {
            assertNotNull("sdnn should not be null", feats.sdnn)
            assertEquals("sdnn mismatch", expectedSdnn, feats.sdnn!!, 1e-3)
        }

        // RMSSD
        if (expectedRmssd != null) {
            assertNotNull("rmssd should not be null", feats.rmssd)
            assertEquals("rmssd mismatch", expectedRmssd, feats.rmssd!!, 1e-3)
        }

        // pNN50
        if (expectedPnn50 != null) {
            assertNotNull("pnn50 should not be null", feats.pnn50)
            assertEquals("pnn50 mismatch", expectedPnn50, feats.pnn50!!, 1e-3)
        }

        // APG b/a Ratio
        if (expectedApgBaRatio != null) {
            assertNotNull("apgBaRatio should not be null", feats.apgBaRatio)
            assertEquals("apgBaRatio mismatch", expectedApgBaRatio, feats.apgBaRatio!!, 1e-3)
        }
    }

    // ── Helper parsing functions ─────────────────────────────────────────────

    private fun parseDoubleArray(json: String, key: String): DoubleArray {
        val pattern = "\"$key\"\\s*:\\s*\\[([^\\]]*)\\]".toRegex()
        val match = pattern.find(json) ?: return doubleArrayOf()
        val listStr = match.groupValues[1].trim()
        if (listStr.isEmpty()) return doubleArrayOf()
        return listStr.split(",").map { it.trim().toDouble() }.toDoubleArray()
    }

    private fun parseIntArray(json: String, key: String): IntArray {
        val pattern = "\"$key\"\\s*:\\s*\\[([^\\]]*)\\]".toRegex()
        val match = pattern.find(json) ?: return intArrayOf()
        val listStr = match.groupValues[1].trim()
        if (listStr.isEmpty()) return intArrayOf()
        return listStr.split(",").map { it.trim().toInt() }.toIntArray()
    }

    private fun parseDouble(json: String, key: String): Double? {
        val pattern = "\"$key\"\\s*:\\s*([0-9eE\\.\\-\\+]+)".toRegex()
        val match = pattern.find(json) ?: return null
        return match.groupValues[1].toDouble()
    }

    private fun extractFeaturesBlock(json: String): String {
        val pattern = "\"features\"\\s*:\\s*\\{([^\\}]*)\\}".toRegex()
        return pattern.find(json)?.groupValues?.get(1) ?: ""
    }

    private fun parseFeatureDouble(block: String, key: String): Double? {
        val pattern = "\"$key\"\\s*:\\s*([0-9eE\\.\\-\\+]+|null)".toRegex()
        val match = pattern.find(block) ?: return null
        val valStr = match.groupValues[1]
        if (valStr == "null") return null
        return valStr.toDouble()
    }
}

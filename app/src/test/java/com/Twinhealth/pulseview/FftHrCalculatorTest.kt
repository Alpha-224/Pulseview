package com.Twinhealth.pulseview

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * JVM unit tests for [FftHrCalculator].
 *
 * Validates the Kotlin DFT implementation against exact values computed by
 * the Python reference (scipy.signal.periodogram + compute_fft_hr) using
 * the script gen_fft_reference.py.
 *
 * These tests run on a standard JVM — no Android/OpenCV dependencies needed.
 */
class FftHrCalculatorTest {

    // ═══════════════════════════════════════════════════════════════════════
    // Test 1: nextPowerOf2 matches Python _next_power_of_2 exactly
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun nextPowerOf2_matchesPythonReference() {
        // Reference values from Python: 2 ** (x - 1).bit_length()
        val cases = mapOf(
            0 to 1,
            1 to 1,
            2 to 2,
            3 to 4,
            4 to 4,
            5 to 8,
            7 to 8,
            8 to 8,
            9 to 16,
            15 to 16,
            16 to 16,
            17 to 32,
            127 to 128,
            128 to 128,
            129 to 256,
            255 to 256,
            256 to 256,
            257 to 512,
            300 to 512,
            512 to 512,
            1000 to 1024,
            1024 to 1024
        )

        for ((input, expected) in cases) {
            assertEquals(
                "nextPowerOf2($input) should be $expected",
                expected,
                FftHrCalculator.nextPowerOf2(input)
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test 2: Periodogram spot checks against scipy reference
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Verifies that [FftHrCalculator.computePeriodogram] produces frequency bins
     * and PSD values matching scipy.signal.periodogram for a 1.2 Hz sine wave.
     *
     * Python reference (gen_fft_reference.py):
     *   f[0] = 0.0, f[1] = 0.05859375, f[256] = 15.0
     *   Pxx[0] = 0.0
     *   Bin k=20 (f=1.171875): Pxx = 3.916355609893799
     */
    @Test
    fun computePeriodogram_matchesScipy() {
        val fs = 30.0
        val numSamples = 300
        val freq = 1.2

        // Generate the same 1.2 Hz sine wave as the Python reference.
        val signal = DoubleArray(numSamples) { n ->
            sin(2.0 * PI * freq * n / fs)
        }

        val nfft = FftHrCalculator.nextPowerOf2(numSamples)
        assertEquals("nfft should be 512", 512, nfft)

        val (freqs, psd) = FftHrCalculator.computePeriodogram(signal, fs, nfft)

        // Number of one-sided bins: nfft/2 + 1 = 257
        assertEquals("Should have 257 frequency bins", 257, freqs.size)
        assertEquals("PSD array should match freq array length", 257, psd.size)

        // Frequency bin checks
        assertEquals("f[0] should be 0.0", 0.0, freqs[0], 1e-10)
        assertEquals("f[1] should be 0.05859375", 0.05859375, freqs[1], 1e-10)
        assertEquals("f[256] should be 15.0", 15.0, freqs[256], 1e-10)

        // DC bin PSD should be ~0 (sine wave has zero mean)
        assertEquals("Pxx[0] should be ~0", 0.0, psd[0], 1e-6)

        // Peak at bin k=20 (f=1.171875 Hz, closest to 1.2 Hz)
        assertEquals("f[20] should be 1.171875", 1.171875, freqs[20], 1e-10)
        assertEquals(
            "Pxx[20] should match scipy reference (3.916355609893799)",
            3.916355609893799,
            psd[20],
            0.001  // Allow small tolerance for float32→float64 accumulation differences
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test 3: computeHr for 1.2 Hz sine → ~70.3125 BPM
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * The primary validation test: a 1.2 Hz sine wave at 30 Hz sampling rate
     * should produce HR ≈ 70.3125 BPM.
     *
     * Python reference: compute_fft_hr → 70.3125 BPM
     *
     * Note: The expected value is 70.3125, NOT 72.0, because the DFT frequency
     * resolution is fs/N = 30/512 ≈ 0.0586 Hz, and the bin closest to 1.2 Hz
     * is bin k=20 at f=1.171875 Hz → 70.3125 BPM. This is the correct behavior
     * matching the Python reference — the spectral resolution quantization means
     * 1.2 Hz falls between bins k=20 (1.171875 Hz) and k=21 (1.23046875 Hz).
     */
    @Test
    fun computeHr_1_2HzSine_matchesPythonReference() {
        val fs = 30.0
        val numSamples = 300
        val freq = 1.2

        val bvp = FloatArray(numSamples) { n ->
            sin(2.0 * PI * freq * n / fs).toFloat()
        }

        val hr = FftHrCalculator.computeHr(bvp, fs = fs)

        assertEquals(
            "HR for 1.2 Hz sine should be 70.3125 BPM (matching Python reference)",
            70.3125,
            hr,
            0.5  // 0.5 BPM tolerance as specified in the task
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test 4: computeHr for 1.5 Hz sine → ~91.40625 BPM
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Python reference: compute_fft_hr(1.5 Hz sine, 300 samples, 30 Hz) → 91.40625 BPM
     */
    @Test
    fun computeHr_1_5HzSine_matchesPythonReference() {
        val fs = 30.0
        val numSamples = 300
        val freq = 1.5

        val bvp = FloatArray(numSamples) { n ->
            sin(2.0 * PI * freq * n / fs).toFloat()
        }

        val hr = FftHrCalculator.computeHr(bvp, fs = fs)

        assertEquals(
            "HR for 1.5 Hz sine should be 91.40625 BPM (matching Python reference)",
            91.40625,
            hr,
            0.5
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test 5: Composite signal — dominant frequency should win
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Composite signal: 1.0 Hz (amplitude 1.0) + 1.8 Hz (amplitude 0.5).
     * The 1.0 Hz component has 4× the power, so the peak should be near 60 BPM.
     *
     * Python reference: compute_fft_hr → 59.765625 BPM
     */
    @Test
    fun computeHr_compositeSignal_picksStrongestComponent() {
        val fs = 30.0
        val numSamples = 300

        val bvp = FloatArray(numSamples) { n ->
            val t = n / fs
            (sin(2.0 * PI * 1.0 * t) + 0.5 * sin(2.0 * PI * 1.8 * t)).toFloat()
        }

        val hr = FftHrCalculator.computeHr(bvp, fs = fs)

        assertEquals(
            "Composite signal should yield ~59.765625 BPM (1.0 Hz dominant, matching Python)",
            59.765625,
            hr,
            0.5
        )
    }
}

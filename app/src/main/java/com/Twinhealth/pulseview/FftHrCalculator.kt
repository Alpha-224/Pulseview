package com.Twinhealth.pulseview

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * FFT-based heart rate calculator for rPPG BVP signals.
 *
 * Faithful Kotlin port of the Python reference:
 * ```python
 * def compute_fft_hr(bvp, fs=30.0, low_pass=0.75, high_pass=2.5):
 *     N = _next_power_of_2(len(bvp))
 *     f, Pxx = scipy.signal.periodogram(bvp, fs=fs, nfft=N, detrend=False)
 *     mask = (f >= low_pass) & (f <= high_pass)
 *     return f[mask][np.argmax(Pxx[mask])] * 60
 * ```
 *
 * Uses a naive O(N²) DFT instead of a third-party FFT library. This is
 * intentional: expected input sizes are ~100–500 BVP samples (a few hundred
 * to a few thousand after zero-padding to next power of 2), so the DFT
 * completes in well under a second on modern phone hardware. The benefit
 * is provable correctness against the scipy reference without needing to
 * validate a separate library's internal conventions.
 */
object FftHrCalculator {

    /**
     * Compute the smallest power of 2 ≥ x.
     *
     * Exact port of the Python reference:
     * ```python
     * def _next_power_of_2(x):
     *     return 1 if x == 0 else 2 ** (x - 1).bit_length()
     * ```
     */
    fun nextPowerOf2(x: Int): Int {
        if (x == 0) return 1
        // (x - 1).bit_length() in Python = 32 - Integer.numberOfLeadingZeros(x - 1) in Java/Kotlin,
        // but we need to handle x=1 correctly: (1-1).bit_length() = 0 → 2^0 = 1
        val v = x - 1
        if (v == 0) return 1
        val bitLen = 32 - Integer.numberOfLeadingZeros(v)
        return 1 shl bitLen
    }

    /**
     * Compute the one-sided power spectral density (periodogram) of a real signal.
     *
     * Matches `scipy.signal.periodogram(sig, fs=fs, nfft=N, detrend=False)` exactly:
     * - Window: boxcar of length `signal.size` (not `nfft`). The default `window='boxcar'`
     *   in scipy creates a window of length `signal.size`, regardless of `nfft`.
     * - Zero-pad signal to length [nfft]
     * - Compute DFT bins k = 0 to nfft/2
     * - PSD: `Pxx[k] = (1.0 / (fs * signal.size)) * |DFT[k]|²`
     *   (scipy normalizes by `fs * sum(win²)` where `sum(boxcar²) = signal.size`)
     * - One-sided scaling: multiply by 2.0 for all bins except DC (k=0) and Nyquist (k=nfft/2)
     * - Frequency bins: `f[k] = k * fs / nfft`
     *
     * @param signal  Input BVP signal (any length, will be zero-padded to [nfft]).
     * @param fs      Sampling frequency in Hz.
     * @param nfft    DFT length (must be ≥ signal.size; typically next power of 2).
     * @return Pair of (frequencies, psd) arrays, each of length nfft/2 + 1.
     */
    fun computePeriodogram(signal: DoubleArray, fs: Double, nfft: Int): Pair<DoubleArray, DoubleArray> {
        val signalLength = signal.size

        // Zero-pad the signal to length nfft.
        val x = DoubleArray(nfft)
        signal.copyInto(x, endIndex = minOf(signalLength, nfft))

        // Number of one-sided frequency bins: 0, 1, ..., nfft/2
        val numBins = nfft / 2 + 1

        val freqs = DoubleArray(numBins)
        val psd = DoubleArray(numBins)

        // Naive DFT for bins k = 0 to nfft/2 only (one-sided for real input).
        for (k in 0 until numBins) {
            var realPart = 0.0
            var imagPart = 0.0
            for (n in 0 until nfft) {
                val angle = -2.0 * PI * k.toDouble() * n.toDouble() / nfft.toDouble()
                realPart += x[n] * cos(angle)
                imagPart += x[n] * sin(angle)
            }

            // PSD = |DFT|² / (fs * sum(win²))
            // For boxcar window of length signalLength: sum(win²) = signalLength.
            // This matches scipy.signal.periodogram's normalization exactly.
            val magnitudeSq = realPart * realPart + imagPart * imagPart
            var pxx = magnitudeSq / (fs * signalLength)

            // One-sided scaling: multiply by 2 for all bins except DC and Nyquist.
            if (k > 0 && k < nfft / 2) {
                pxx *= 2.0
            }

            freqs[k] = k.toDouble() * fs / nfft.toDouble()
            psd[k] = pxx
        }

        return Pair(freqs, psd)
    }

    /**
     * Compute heart rate in BPM from a BVP signal using FFT-based periodogram.
     *
     * Matches `fft_hr()` in `infer_efficientphys.py`.
     *
     * When [posHrHint] is provided (POS-guided peak-correction mode):
     *   - Take the top-5 spectral peaks by power within the band.
     *   - Return the one whose BPM is closest to [posHrHint] within ±15 BPM.
     *   - If none of the top-5 is within ±15 BPM, fall back to the global argmax.
     *
     * When [posHrHint] is null: return the global argmax (original behavior).
     *
     * @param bvp        BVP signal from ONNX model output (concatenated chunks).
     * @param fs         Sampling frequency in Hz.
     * @param lowPass    Lower bound of HR frequency band in Hz (default 0.75 → 45 BPM).
     * @param highPass   Upper bound of HR frequency band in Hz (default 2.5 → 150 BPM).
     * @param posHrHint  Optional POS HR estimate for peak-correction (BPM). Null = no correction.
     * @return Heart rate in beats per minute (BPM).
     * @throws IllegalArgumentException if no frequency bins fall within the band.
     */
    fun computeHr(
        bvp: FloatArray,
        fs: Double = 30.0,
        lowPass: Double = 0.75,
        highPass: Double = 2.5,
        posHrHint: Double? = null
    ): Double {
        val signal = DoubleArray(bvp.size) { bvp[it].toDouble() }
        val nfft = nextPowerOf2(signal.size)
        val (freqs, psd) = computePeriodogram(signal, fs, nfft)

        // Collect all in-band bins.
        data class Bin(val idx: Int, val freqHz: Double, val power: Double)
        val inBand = freqs.indices
            .filter { freqs[it] >= lowPass && freqs[it] <= highPass }
            .map { Bin(it, freqs[it], psd[it]) }

        require(inBand.isNotEmpty()) {
            "No frequency bins in [$lowPass, $highPass] Hz. " +
                    "Signal length=${bvp.size}, nfft=$nfft, fs=$fs."
        }

        if (posHrHint != null) {
            // POS-guided peak correction (matching Python fft_hr() with pos_hr_hint).
            // Take top-5 in-band bins by descending power.
            val top5 = inBand.sortedByDescending { it.power }.take(5)
            var bestBin: Bin? = null
            var bestDist = Double.MAX_VALUE
            for (bin in top5) {
                val dist = kotlin.math.abs(bin.freqHz * 60.0 - posHrHint)
                if (dist < bestDist && dist <= 15.0) {
                    bestDist = dist
                    bestBin = bin
                }
            }
            if (bestBin != null) return bestBin.freqHz * 60.0
            // None within ±15 BPM — fall back to returning the POS HR estimate itself.
            return posHrHint
        }

        // Global argmax (original behavior, also fallback when posHrHint fails).
        return inBand.maxBy { it.power }.freqHz * 60.0
    }

    /**
     * Compute the raw (uncorrected) FFT argmax HR — always ignores [posHrHint].
     *
     * Used for diagnostic logging to show what the raw spectrum picks before
     * POS-guided peak correction is applied.
     */
    fun computeHrRaw(
        bvp: FloatArray,
        fs: Double = 30.0,
        lowPass: Double = 0.75,
        highPass: Double = 2.5
    ): Double = computeHr(bvp, fs, lowPass, highPass, posHrHint = null)
}

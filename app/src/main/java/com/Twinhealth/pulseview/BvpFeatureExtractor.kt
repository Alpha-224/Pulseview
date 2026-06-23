package com.Twinhealth.pulseview

import android.util.Log
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Port of the HRV and APG feature extraction pipeline from bvp_features.py.
 */
object BvpFeatureExtractor {

    private const val TAG = "BvpFeatureExtractor"

    data class Features(
        val ibiMeanMs: Double?,
        val ibiStdMs: Double?,
        val rmssd: Double?,
        val sdnn: Double?,
        val pnn50: Double?,
        val apgBaRatio: Double?
    )

    /**
     * Extracts HRV features and APG b/a ratio from a raw BVP signal using chunk-based
     * extraction and median aggregation, matching Python's extract_features_per_chunk.
     *
     * @param bvp The raw concatenated BVP signal.
     * @param fs The sampling frequency in Hz.
     * @return Features data class with computed values, or nulls if they cannot be computed.
     */
    fun extractFeatures(bvp: FloatArray, fs: Double): Features {
        val n = bvp.size
        // Require at least 3 seconds of data to run anything
        if (n < (3 * fs).toInt()) {
            return Features(null, null, null, null, null, null)
        }

        // Time-based chunking matching Python's 160 samples at 30 Hz -> ~5.33 seconds
        val chunkSize = kotlin.math.round(fs * (160.0 / 30.0)).toInt()

        // If the signal is too short to construct a chunk of 5.33s, run in single-chunk mode
        if (n < chunkSize) {
            return extractFeaturesSingleChunk(bvp, fs)
        }

        val numChunks = n / chunkSize
        
        if (BuildConfig.DEBUG) {
            logDebug("extractFeatures (Chunked): Signal size: $n samples, fs: $fs, chunkSize: $chunkSize, numChunks: $numChunks")
        }

        val ibiMeanMsList = mutableListOf<Double>()
        val ibiStdMsList = mutableListOf<Double>()
        val rmssdList = mutableListOf<Double>()
        val sdnnList = mutableListOf<Double>()
        val pnn50List = mutableListOf<Double>()
        val apgBaRatioList = mutableListOf<Double>()

        for (i in 0 until numChunks) {
            val start = i * chunkSize
            val chunk = FloatArray(chunkSize) { bvp[start + it] }
            val feats = extractFeaturesSingleChunk(chunk, fs, "Chunk $i")
            if (BuildConfig.DEBUG) {
                logDebug("extractFeatures (Chunked) -> Chunk $i: ibiMean=${feats.ibiMeanMs}, ibiStd=${feats.ibiStdMs}, rmssd=${feats.rmssd}, sdnn=${feats.sdnn}, pnn50=${feats.pnn50}, apgRatio=${feats.apgBaRatio}")
            }
            feats.ibiMeanMs?.let { ibiMeanMsList.add(it) }
            feats.ibiStdMs?.let { ibiStdMsList.add(it) }
            feats.rmssd?.let { rmssdList.add(it) }
            feats.sdnn?.let { sdnnList.add(it) }
            feats.pnn50?.let { pnn50List.add(it) }
            feats.apgBaRatio?.let { apgBaRatioList.add(it) }
        }

        if (BuildConfig.DEBUG) {
            logDebug("extractFeatures (Chunked) -> Valid chunk counts: ibiMean=${ibiMeanMsList.size}, ibiStd=${ibiStdMsList.size}, rmssd=${rmssdList.size}, sdnn=${sdnnList.size}, pnn50=${pnn50List.size}, apgRatio=${apgBaRatioList.size}")
        }

        // Aggregate with median. Requires at least 3 valid chunks for each metric, matching Python infer.py
        return Features(
            ibiMeanMs = calculateMedian(ibiMeanMsList),
            ibiStdMs = calculateMedian(ibiStdMsList),
            rmssd = calculateMedian(rmssdList),
            sdnn = calculateMedian(sdnnList),
            pnn50 = calculateMedian(pnn50List),
            apgBaRatio = calculateMedian(apgBaRatioList)
        )
    }

    private fun calculateMedian(list: List<Double>): Double? {
        if (list.size < 3) return null
        val sorted = list.sorted()
        val m = sorted.size
        return if (m % 2 == 1) {
            sorted[m / 2]
        } else {
            (sorted[m / 2 - 1] + sorted[m / 2]) / 2.0
        }
    }

    /**
     * Extracts HRV features and APG b/a ratio from a raw BVP signal (single-chunk mode).
     *
     * @param bvp The raw BVP signal.
     * @param fs The sampling frequency in Hz.
     * @return Features data class with computed values, or nulls if they cannot be computed.
     */
    fun extractFeaturesSingleChunk(bvp: FloatArray, fs: Double, chunkLabel: String = "Single"): Features {
        val n = bvp.size
        // Require at least 3 seconds of data
        if (n < (3 * fs).toInt()) {
            return Features(null, null, null, null, null, null)
        }

        val bvpDouble = DoubleArray(n) { bvp[it].toDouble() }

        // 0. Z-score standardize the BVP signal to ensure peak detection works correctly
        // regardless of the input signal amplitude (scipy.signal.find_peaks prominence is calibrated for std=1.0)
        val bvpMean = bvpDouble.average()
        val bvpStd = sampleStd(bvpDouble)
        val bvpStandardized = DoubleArray(n) { i ->
            if (bvpStd > 0.0) (bvpDouble[i] - bvpMean) / bvpStd else 0.0
        }

        // 1. Detrend (remove slow baseline drift)
        val bvpDetrended = detrend(bvpStandardized)

        // 2. HR band: bandpass 0.75-2.5 Hz
        val (b, a) = PosHrCalculator.selectCoefficients(fs)
        val zi = PosHrCalculator.selectZi(fs)
        val bvpHr = PosHrCalculator.filtfilt(b, a, bvpDetrended, zi)

        // 3. Peak detection
        // Enforce a minimum 0.5-second refractory period (500ms).
        // At variable/lower frame rates (typically 20-25Hz), truncating (fs * 0.5) to an integer
        // systematically under-shoots the intended 500ms refractory period (e.g. 10 samples at 20.93Hz = 477.8ms).
        // This allows secondary waveform features (e.g., dicrotic notch) to be falsely detected as separate
        // heartbeats, which inflates HRV variability metrics (RMSSD, pNN50) artificially.
        // Using ceil ensures the refractory window is never shorter than the intended 500ms.
        val minDistance = ceil(fs * 0.5).toInt()
        val meanHr = bvpHr.average()
        val rawPeaks = findPeaks(
            bvpHr,
            distance = minDistance,
            prominence = 0.6,
            height = meanHr,
            wlen = (fs * 10).toInt()
        )

        if (BuildConfig.DEBUG) {
            val minVal = bvpHr.minOrNull() ?: 0.0
            val maxVal = bvpHr.maxOrNull() ?: 0.0
            val stdDev = sampleStd(bvpHr)
            logDebug("extractFeatures ($chunkLabel): BVP signal length: $n samples")
            logDebug("extractFeatures ($chunkLabel): Signal stats - min: $minVal, max: $maxVal, mean: $meanHr, std: $stdDev")
            logDebug("extractFeatures ($chunkLabel): Parameters: fs=$fs, distance=$minDistance, prominence=0.6, height=$meanHr, wlen=${(fs * 10).toInt()}")
            logDebug("extractFeatures ($chunkLabel): Raw peaks detected: ${rawPeaks.size}, indices: ${rawPeaks.joinToString(", ")}")
        }

        // Physiological IBI filter: discard peaks that produce IBIs outside 333ms-1500ms
        var peaks = rawPeaks
        if (peaks.size >= 2) {
            val ibiMsRaw = DoubleArray(peaks.size - 1) { i ->
                (peaks[i + 1] - peaks[i]) / fs * 1000.0
            }
            val validMask = BooleanArray(ibiMsRaw.size) { i ->
                ibiMsRaw[i] in 333.0..1500.0
            }
            val keep = BooleanArray(peaks.size) { true }
            for (i in validMask.indices) {
                if (!validMask[i]) {
                    keep[i] = false
                    keep[i + 1] = false
                }
            }
            val filteredPeaks = mutableListOf<Int>()
            for (i in peaks.indices) {
                if (keep[i]) {
                    filteredPeaks.add(peaks[i])
                }
            }
            peaks = filteredPeaks.toIntArray()
            
            if (BuildConfig.DEBUG) {
                logDebug("extractFeatures ($chunkLabel): Filtered peaks: ${peaks.size}, indices: ${peaks.joinToString(", ")}")
                logDebug("extractFeatures ($chunkLabel): Raw IBIs (ms): ${ibiMsRaw.joinToString(", ") { String.format("%.1f", it) }}")
            }
        }

        // 4. HRV metrics (require >= 3 peaks, i.e., >= 2 IBI intervals)
        var ibiMeanMs: Double? = null
        var ibiStdMs: Double? = null
        var sdnn: Double? = null
        var rmssd: Double? = null
        var pnn50: Double? = null

        if (peaks.size >= 3) {
            val ibiMs = DoubleArray(peaks.size - 1) { i ->
                (peaks[i + 1] - peaks[i]) / fs * 1000.0
            }

            val meanVal = ibiMs.average()
            ibiMeanMs = meanVal

            val stdVal = sampleStd(ibiMs)
            sdnn = stdVal
            ibiStdMs = stdVal

            val successiveDiff = DoubleArray(ibiMs.size - 1) { i ->
                ibiMs[i + 1] - ibiMs[i]
            }

            var sumSqDiff = 0.0
            for (v in successiveDiff) {
                sumSqDiff += v * v
            }
            rmssd = sqrt(sumSqDiff / successiveDiff.size)

            var nn50Count = 0
            for (v in successiveDiff) {
                if (abs(v) > 50.0) {
                    nn50Count++
                }
            }
            pnn50 = nn50Count.toDouble() / successiveDiff.size * 100.0
        } else if (peaks.size == 2) {
            // Under 3 peaks, but we still have exactly 1 IBI, we can report ibiMeanMs
            val ibiMs = (peaks[1] - peaks[0]) / fs * 1000.0
            ibiMeanMs = ibiMs
        }

        // 5. APG b/a ratio of the acceleration plethysmogram
        var apgBaRatio: Double? = null
        try {
            val vpg = gradient(bvpHr)
            val apg = gradient(vpg)

            val aPeaks = findPeaks(apg, distance = ceil(fs * 0.5).toInt())
            val bTroughs = findPeaks(negate(apg), distance = ceil(fs * 0.5).toInt())

            if (aPeaks.isNotEmpty() && bTroughs.isNotEmpty()) {
                val aIdx = aPeaks[0]
                val bCandidates = bTroughs.filter { it > aIdx }
                if (bCandidates.isNotEmpty()) {
                    val bIdx = bCandidates[0]
                    val aAmp = apg[aIdx]
                    val bAmp = apg[bIdx]
                    if (aAmp != 0.0) {
                        apgBaRatio = bAmp / aAmp
                    }
                }
            }
        } catch (_: Exception) {}

        return Features(
            ibiMeanMs = ibiMeanMs,
            ibiStdMs = ibiStdMs,
            rmssd = rmssd,
            sdnn = sdnn,
            pnn50 = pnn50,
            apgBaRatio = apgBaRatio
        )
    }

    /**
     * Performs linear detrending on the signal.
     * Matches scipy.signal.detrend behavior.
     */
    fun detrend(signal: DoubleArray): DoubleArray {
        val n = signal.size
        if (n < 2) return signal.copyOf()
        val xMean = (n - 1) / 2.0
        var sumXY = 0.0
        var sumY = 0.0
        for (i in 0 until n) {
            val y = signal[i]
            sumXY += (i - xMean) * y
            sumY += y
        }
        val denom = (n.toDouble() * (n.toDouble() * n.toDouble() - 1.0)) / 12.0
        val m = sumXY / denom
        val c = (sumY / n.toDouble()) - m * xMean

        val detrended = DoubleArray(n)
        for (i in 0 until n) {
            detrended[i] = signal[i] - (m * i + c)
        }
        return detrended
    }

    /**
     * Peak detection function emulating scipy.signal.find_peaks.
     */
    fun findPeaks(
        x: DoubleArray,
        distance: Int,
        prominence: Double = 0.0,
        height: Double? = null,
        wlen: Int = 0
    ): IntArray {
        val n = x.size
        if (n < 3) return intArrayOf()

        // 1. Find all local maxima
        val localMaxIdxs = mutableListOf<Int>()
        for (i in 1 until n - 1) {
            if (x[i] > x[i - 1] && x[i] > x[i + 1]) {
                localMaxIdxs.add(i)
            }
        }

        // 2. Filter by height
        var candidates = if (height != null) {
            localMaxIdxs.filter { x[it] >= height }
        } else {
            localMaxIdxs
        }

        // 3. Filter by prominence
        if (prominence > 0.0 && wlen > 0) {
            val halfW = (wlen + 1) / 2 - 1
            candidates = candidates.filter { i ->
                // left base
                var leftLimit = maxOf(0, i - halfW)
                for (j in i - 1 downTo leftLimit) {
                    if (x[j] > x[i]) {
                        leftLimit = j
                        break
                    }
                }
                var leftMin = x[i]
                for (j in leftLimit..i) {
                    if (x[j] < leftMin) {
                        leftMin = x[j]
                    }
                }

                // right base
                var rightLimit = minOf(n - 1, i + halfW)
                for (j in i + 1..rightLimit) {
                    if (x[j] > x[i]) {
                        rightLimit = j
                        break
                    }
                }
                var rightMin = x[i]
                for (j in i..rightLimit) {
                    if (x[j] < rightMin) {
                        rightMin = x[j]
                    }
                }

                val lowestContour = maxOf(leftMin, rightMin)
                val peakProminence = x[i] - lowestContour
                peakProminence >= prominence
            }
        }

        // 4. Filter by distance
        val sortedByHeight = candidates.sortedByDescending { x[it] }
        val selectedPeaks = mutableListOf<Int>()
        for (candidate in sortedByHeight) {
            var keep = true
            for (selected in selectedPeaks) {
                if (abs(candidate - selected) < distance) {
                    keep = false
                    break
                }
            }
            if (keep) {
                selectedPeaks.add(candidate)
            }
        }

        return selectedPeaks.sorted().toIntArray()
    }

    /**
     * Calculates the gradient of a 1D array.
     * Matches np.gradient behavior.
     */
    fun gradient(x: DoubleArray): DoubleArray {
        val n = x.size
        if (n == 0) return doubleArrayOf()
        if (n == 1) return doubleArrayOf(0.0)
        val grad = DoubleArray(n)
        grad[0] = x[1] - x[0]
        for (i in 1 until n - 1) {
            grad[i] = (x[i + 1] - x[i - 1]) / 2.0
        }
        grad[n - 1] = x[n - 1] - x[n - 2]
        return grad
    }

    /**
     * Computes sample standard deviation (ddof=1).
     */
    private fun sampleStd(arr: DoubleArray): Double {
        if (arr.size < 2) return 0.0
        val mean = arr.average()
        var sumSqDiff = 0.0
        for (v in arr) {
            val diff = v - mean
            sumSqDiff += diff * diff
        }
        return sqrt(sumSqDiff / (arr.size - 1))
    }

    /**
     * Negates all elements in the array.
     */
    private fun negate(arr: DoubleArray): DoubleArray {
        return DoubleArray(arr.size) { -arr[it] }
    }

    private fun logDebug(msg: String) {
        try {
            Log.d(TAG, msg)
        } catch (e: Throwable) {
            println("[$TAG] $msg")
        }
    }
}

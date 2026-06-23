package com.Twinhealth.pulseview

/**
 * Carries the results of the full rPPG inference pipeline from
 * [CameraScreen] to [ResultsScreen].
 *
 * All values are real measurements — no dummy/hardcoded data.
 */
data class InferenceResult(
    /**
     * POS-corrected heart rate in BPM. When a POS estimate is available and a
     * spectral peak within ±15 BPM is found, this differs from [heartRateBpmRaw].
     * This is the value displayed to the user.
     */
    val heartRateBpm: Double,

    /**
     * Raw EfficientPhys FFT argmax HR before POS peak-correction.
     * Logged separately for diagnostics. Equals [heartRateBpm] when POS is unavailable.
     */
    val heartRateBpmRaw: Double = heartRateBpm,

    /**
     * Independent POS HR estimate (mean across valid chunks), or null if POS failed.
     * Used for peak-correction and the reliability gate.
     */
    val posHeartRateBpm: Double? = null,

    /**
     * True when |correctedHR - posHR| ≤ 10 BPM, indicating the two independent
     * methods agree. False when they diverge (suspect motion artifact or model failure).
     * Null when POS is unavailable (no reliability determination possible).
     */
    val signalReliable: Boolean? = null,

    /** Wall-clock recording duration in seconds. */
    val recordingDurationSeconds: Double,

    /** Total number of video frames captured during recording. */
    val frameCount: Int,

    /** Actual achieved frames per second (frameCount / duration). */
    val achievedFps: Double,

    /** Concatenated output BVP signal array. */
    val bvpSignal: FloatArray = floatArrayOf(),

    /** Mean of inter-beat intervals in milliseconds. */
    val ibiMeanMs: Double? = null,

    /** Standard deviation of inter-beat intervals in milliseconds. */
    val ibiStdMs: Double? = null,

    /** Root mean square of successive IBI differences. */
    val rmssd: Double? = null,

    /** Standard deviation of the full IBI array. */
    val sdnn: Double? = null,

    /** Percentage of consecutive IBI differences exceeding 50ms. */
    val pnn50: Double? = null,

    /** Acceleration plethysmogram (APG) b/a ratio. */
    val apgBaRatio: Double? = null
)

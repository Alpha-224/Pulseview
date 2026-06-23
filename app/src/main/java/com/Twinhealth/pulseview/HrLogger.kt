package com.Twinhealth.pulseview

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Data class representing a heart rate reading logged to Firestore.
 * Extended to include additional clinical parameters and a default constructor for Firestore deserialization.
 *
 * @property timestamp Unix epoch milliseconds. Callers MUST pass this explicitly on write.
 * The default of 0L is intentionally a sentinel value (Jan 1 1970) to expose caller bugs in the console
 * rather than silently defaulting to the current time and hiding missing parameters.
 */
data class HrReading(
    val heartRateBpm: Double = 0.0,
    val timestamp: Long = 0L,
    val deviceId: String = "",
    val recordingDurationSeconds: Double = 0.0,
    val frameCount: Int = 0,
    val achievedFps: Double = 0.0,
    val signalQualityReliable: Boolean? = null,
    val heartRateBpmRaw: Double? = null,
    val posHeartRateBpm: Double? = null,
    val bvpSignal: List<Float>? = null,
    val respiratoryRate: Int? = null,
    val ibiMeanMs: Double? = null,
    val ibiStdMs: Double? = null,
    val rmssd: Double? = null,
    val sdnn: Double? = null,
    val pnn50: Double? = null,
    val apgBaRatio: Double? = null
)

/**
 * Self-contained logging utility for posting heart rate readings to Firestore.
 */
class HrLogger {
    // Obtain reference to the Firestore instance using the KTX extension API
    private val db = Firebase.firestore

    /**
     * Logs the given [reading] to the "hr_readings" collection in Firestore.
     *
     * Firestore has offline persistence enabled by default, so writes made while offline
     * will automatically sync once connectivity returns. The app doesn't need to manually
     * queue/retry failed writes; Firestore handles this internally.
     *
     * @return Result.success(Unit) on successful write, or Result.failure(exception) if it fails.
     */
    suspend fun logReading(reading: HrReading): Result<Unit> {
        return try {
            db.collection("hr_readings")
                .add(reading)
                .await() // Convert Task<DocumentReference> to a coroutine suspend call
            Result.success(Unit)
        } catch (e: Exception) {
            // Log the error silently to Logcat but do not propagate or show to the user,
            // as logging failure should not interrupt the core user flow.
            Log.w("HrLogger", "Failed to log reading", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val PREFS_NAME = "pulseview_prefs"
        private const val KEY_DEVICE_ID = "device_id"

        /**
         * Returns the current authenticated Firebase user's UID.
         * NOTE: This is now an authenticated user ID, not an anonymous device ID.
         * The field name is kept as deviceId for now to avoid a larger refactor of the Firestore schema.
         */
        fun getOrCreateDeviceId(context: Context): String {
            return com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "anonymous"
        }
    }
}

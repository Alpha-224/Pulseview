package com.Twinhealth.pulseview

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.Twinhealth.pulseview.ui.theme.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

/**
 * Screen displaying historical heart rate readings queried from Firestore.
 * Implements a time-window filter row ("24H", "7D", "30D", "1Y") and supports
 * tapping a row to expand inline details using shared clinical cards from ResultsScreen.
 */
@Composable
fun HistoryScreen() {
    var selectedWindow by remember { mutableStateOf("7D") }
    var readings by remember { mutableStateOf<List<HrReading>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var expandedTimestamp by remember { mutableStateOf<Long?>(null) }

    val db = remember { Firebase.firestore }
    val auth = remember { FirebaseAuth.getInstance() }
    val userId = auth.currentUser?.uid ?: ""

    // Format timestamps readably (e.g. Jun 19, 2:34 PM)
    val dateFormatter = remember { SimpleDateFormat("MMM dd, h:mm a", Locale.US) }

    // Re-run the query whenever selectedWindow or userId changes
    LaunchedEffect(userId, selectedWindow) {
        if (userId.isEmpty()) {
            errorMessage = "User not logged in."
            return@LaunchedEffect
        }

        isLoading = true
        errorMessage = null
        expandedTimestamp = null

        val now = System.currentTimeMillis()
        val startTimestamp = when (selectedWindow) {
            "24H" -> now - 24 * 60 * 60 * 1000L
            "7D" -> now - 7 * 24 * 60 * 60 * 1000L
            "30D" -> now - 30L * 24 * 60 * 60 * 1000L
            "1Y" -> now - 365L * 24 * 60 * 60 * 1000L
            else -> now - 7 * 24 * 60 * 60 * 1000L
        }

        try {
            val snapshot = db.collection("hr_readings")
                .whereEqualTo("deviceId", userId)
                .whereGreaterThanOrEqualTo("timestamp", startTimestamp)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .await()

            readings = snapshot.documents.mapNotNull { doc ->
                doc.toObject(HrReading::class.java)
            }
        } catch (e: Exception) {
            Log.e("HistoryScreen", "Failed to query heart rate readings from Firestore", e)
            errorMessage = e.message
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Screen Title
        Text(
            text = "Scan History",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.textPrimary
        )

        // Pill-shaped filter row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("24H", "7D", "30D", "1Y").forEach { option ->
                val isSelected = selectedWindow == option
                Box(
                    modifier = Modifier
                        .background(
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.card,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.border,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable { selectedWindow = option }
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = option,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Content Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            } else if (errorMessage != null) {
                val isIndexError = errorMessage!!.contains("index", ignoreCase = true) || errorMessage!!.contains("FAILED_PRECONDITION", ignoreCase = true)
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .height(IntrinsicSize.Min)
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.error)
                    )
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (isIndexError) "INDEX CREATION REQUIRED" else "QUERY ERROR",
                            style = TextStyle(
                                fontFamily = IbmPlexSans,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                letterSpacing = 0.07.em,
                                color = MaterialTheme.colorScheme.error
                            )
                        )
                        Text(
                            text = if (isIndexError) {
                                "The database is setting up a composite index for this query. " +
                                "Please check Android Logcat to find and click the Firestore index creation URL."
                            } else {
                                errorMessage!!
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.textPrimary,
                            lineHeight = 20.sp
                        )
                    }
                }
            } else if (readings.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.textMuted,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "No scans in this time period",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = IbmPlexSans,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.textMuted
                        ),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(readings, key = { it.timestamp }) { reading ->
                        val isExpanded = expandedTimestamp == reading.timestamp
                        HistoryRowCard(
                            reading = reading,
                            dateFormatter = dateFormatter,
                            isExpanded = isExpanded,
                            onToggleExpand = {
                                expandedTimestamp = if (isExpanded) null else reading.timestamp
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Card displaying an individual historical heart rate reading.
 * Tapping toggles inline clinical details expansion.
 */
@Composable
private fun HistoryRowCard(
    reading: HrReading,
    dateFormatter: SimpleDateFormat,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val inferenceResult = remember(reading) {
        InferenceResult(
            heartRateBpm = reading.heartRateBpm,
            heartRateBpmRaw = reading.heartRateBpmRaw ?: reading.heartRateBpm,
            posHeartRateBpm = reading.posHeartRateBpm,
            signalReliable = reading.signalQualityReliable,
            recordingDurationSeconds = reading.recordingDurationSeconds,
            frameCount = reading.frameCount,
            achievedFps = reading.achievedFps,
            bvpSignal = reading.bvpSignal?.toFloatArray() ?: floatArrayOf(),
            ibiMeanMs = reading.ibiMeanMs,
            ibiStdMs = reading.ibiStdMs,
            rmssd = reading.rmssd,
            sdnn = reading.sdnn,
            pnn50 = reading.pnn50,
            apgBaRatio = reading.apgBaRatio
        )
    }

    Card(
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.card
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleExpand() }
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Collapsed view summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Timestamp and Respiratory Rate
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = dateFormatter.format(Date(reading.timestamp)),
                        style = TextStyle(
                            fontFamily = IbmPlexMono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.textPrimary
                        )
                    )
                    Text(
                        text = "${reading.respiratoryRate ?: 18} rpm respiration",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.textMuted
                    )
                }

                // Heart Rate and Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            text = "${reading.heartRateBpm.toInt()}",
                            style = TextStyle(
                                fontFamily = DmSerifDisplay,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "bpm",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.textMuted,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }

                    SmallStatusBadge(signalQualityReliable = reading.signalQualityReliable)
                }
            }

            // Expanded view details
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(durationMillis = 300)),
                exit = shrinkVertically(animationSpec = tween(durationMillis = 300))
            ) {
                Column(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.border)

                    // Display waveform if we have raw BVP signal data
                    if (inferenceResult.bvpSignal.isNotEmpty()) {
                        BvpWaveformCard(bvpSignal = inferenceResult.bvpSignal)
                    }

                    SignalMetricsCard(result = inferenceResult, respiratoryRate = reading.respiratoryRate ?: 18)
                    PosQualityGateCard(result = inferenceResult)
                    ModelInfoCard(result = inferenceResult)
                }
            }
        }
    }
}

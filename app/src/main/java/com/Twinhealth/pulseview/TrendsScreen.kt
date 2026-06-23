package com.Twinhealth.pulseview

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
 * Real Trends Screen tab.
 * Gates trend visualization until the user has recorded scans on at least 3 distinct calendar days.
 * Plots heart rate or respiratory rate chronologically in a custom line chart using Canvas.
 */
@Composable
fun TrendsScreen() {
    var allReadings by remember { mutableStateOf<List<HrReading>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // User selection state (default window: 7D, default metric: Heart Rate)
    var selectedWindow by remember { mutableStateOf("7D") }
    var selectedMetric by remember { mutableStateOf("HR") } // "HR" or "RR"

    val db = remember { Firebase.firestore }
    val auth = remember { FirebaseAuth.getInstance() }
    val userId = auth.currentUser?.uid ?: ""

    // Format for local calendar dates
    val localDateFormat = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
    }

    // Fetch all scans for the user on launch or user change
    LaunchedEffect(userId) {
        if (userId.isEmpty()) {
            errorMessage = "User not logged in."
            return@LaunchedEffect
        }

        isLoading = true
        errorMessage = null

        try {
            // Fetch all scans (all-time) to check gating and perform in-memory filtering
            val snapshot = db.collection("hr_readings")
                .whereEqualTo("deviceId", userId)
                .get()
                .await()

            allReadings = snapshot.documents.mapNotNull { doc ->
                doc.toObject(HrReading::class.java)
            }.sortedBy { it.timestamp }
        } catch (e: Exception) {
            Log.e("TrendsScreen", "Failed to query readings for trends", e)
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
            text = "Trends",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.textPrimary
        )

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
            } else {
                // Calculate unique calendar days represented
                val distinctDays = allReadings.map { localDateFormat.format(Date(it.timestamp)) }.toSet()
                val distinctDaysCount = distinctDays.size

                if (distinctDaysCount < 3) {
                    // Gating State Layout
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Card(
                            shape = RoundedCornerShape(4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.card
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(64.dp)
                                )

                                Text(
                                    text = "Keep scanning to unlock trends",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.textPrimary,
                                    textAlign = TextAlign.Center
                                )

                                Text(
                                    text = "Record at least one scan on 3 different days to see meaningful trends in your heart rate and respiratory rate over time. You've recorded on $distinctDaysCount of 3 days so far.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.textMuted,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 22.sp
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                LinearProgressIndicator(
                                    progress = { (distinctDaysCount / 3f).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth(0.8f)
                                        .height(8.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                } else {
                    // Real Trends Screen UI
                    val now = System.currentTimeMillis()
                    val startTimestamp = when (selectedWindow) {
                        "7D" -> now - 7 * 24 * 60 * 60 * 1000L
                        "30D" -> now - 30L * 24 * 60 * 60 * 1000L
                        "1Y" -> now - 365L * 24 * 60 * 60 * 1000L
                        "ALL" -> 0L
                        else -> now - 7 * 24 * 60 * 60 * 1000L
                    }

                    // Filter and sort readings in memory chronologically
                    val filteredReadings = remember(allReadings, selectedWindow) {
                        allReadings
                            .filter { it.timestamp >= startTimestamp }
                            .sortedBy { it.timestamp }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // 1. Time Window Selector Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("7D", "30D", "1Y", "ALL").forEach { option ->
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

                        // 2. Metric Selector Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                Pair("HR", "Heart Rate"),
                                Pair("RR", "Respiratory Rate")
                            ).forEach { (code, label) ->
                                val isSelected = selectedMetric == code
                                Box(
                                    modifier = Modifier
                                        .background(
                                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.card,
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.border,
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .clickable { selectedMetric = code }
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                        .weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.textPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        if (filteredReadings.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(240.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.border, RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No readings during this period",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.textMuted
                                )
                            }
                        } else {
                            // 3. Trends Line Chart
                            TrendsChart(
                                readings = filteredReadings,
                                selectedMetric = selectedMetric,
                                modifier = Modifier.fillMaxWidth()
                            )

                            // 4. Summary Stats Section
                            val values = filteredReadings.map {
                                if (selectedMetric == "HR") it.heartRateBpm else (it.respiratoryRate ?: 18).toDouble()
                            }
                            val avgVal = values.average()
                            val minVal = values.minOrNull() ?: 0.0
                            val maxVal = values.maxOrNull() ?: 0.0
                            val unit = if (selectedMetric == "HR") "bpm" else "rpm"

                            Card(
                                shape = RoundedCornerShape(4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.card
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.border),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Text(
                                        text = "SUMMARY STATISTICS",
                                        style = TextStyle(
                                            fontFamily = IbmPlexSans,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 11.sp,
                                            letterSpacing = 0.07.em,
                                            color = MaterialTheme.colorScheme.textMuted
                                        )
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        StatColumn(label = "Average", value = avgVal, unit = unit, weight = 1f)
                                        StatColumn(label = "Minimum", value = minVal, unit = unit, weight = 1f)
                                        StatColumn(label = "Maximum", value = maxVal, unit = unit, weight = 1f)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatColumn(
    label: String,
    value: Double,
    unit: String,
    weight: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = TextStyle(
                fontFamily = IbmPlexSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                letterSpacing = 0.05.em,
                color = MaterialTheme.colorScheme.textMuted
            )
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "${value.toInt()}",
                style = TextStyle(
                    fontFamily = DmSerifDisplay,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = unit,
                style = TextStyle(
                    fontFamily = IbmPlexSans,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.textMuted
                ),
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
    }
}

@Composable
fun TrendsChart(
    readings: List<HrReading>,
    selectedMetric: String,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val accentColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.border.copy(alpha = 0.5f)
    val textColor = MaterialTheme.colorScheme.textMuted

    val values = remember(readings, selectedMetric) {
        readings.map {
            if (selectedMetric == "HR") it.heartRateBpm else (it.respiratoryRate ?: 18).toDouble()
        }
    }

    val minVal = remember(values) { values.minOrNull() ?: 60.0 }
    val maxVal = remember(values) { values.maxOrNull() ?: 100.0 }
    val valRange = maxVal - minVal

    // Add padding to y axis so chart doesn't touch edges
    val padY = if (valRange != 0.0) valRange * 0.15 else 10.0
    val adjustedMinY = (minVal - padY).toFloat()
    val adjustedMaxY = (maxVal + padY).toFloat()
    val adjustedRangeY = adjustedMaxY - adjustedMinY

    val dateFormatter = remember { SimpleDateFormat("MM/dd", Locale.US) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(Color.Black.copy(alpha = 0.03f))
            .border(1.dp, MaterialTheme.colorScheme.border, RoundedCornerShape(4.dp))
            .padding(16.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Margins in pixels
            val leftMargin = 45.dp.toPx()
            val bottomMargin = 25.dp.toPx()
            val topMargin = 10.dp.toPx()
            val rightMargin = 10.dp.toPx()

            val plotWidth = width - leftMargin - rightMargin
            val plotHeight = height - topMargin - bottomMargin

            // 1. Draw Grid Lines and Y-Axis Labels
            val yLevels = if (valRange == 0.0) {
                listOf(minVal)
            } else {
                listOf(minVal, minVal + valRange / 2, maxVal)
            }

            yLevels.forEach { level ->
                val levelY = topMargin + plotHeight - ((level - adjustedMinY) / adjustedRangeY).toFloat() * plotHeight
                
                // Grid line
                drawLine(
                    color = gridColor,
                    start = Offset(leftMargin, levelY),
                    end = Offset(leftMargin + plotWidth, levelY),
                    strokeWidth = 1.dp.toPx()
                )

                // Y label text
                val labelText = "${level.toInt()}"
                val textLayout = textMeasurer.measure(
                    text = labelText,
                    style = TextStyle(
                        fontFamily = IbmPlexMono,
                        fontSize = 10.sp,
                        color = textColor
                    )
                )
                drawText(
                    textLayoutResult = textLayout,
                    topLeft = Offset(
                        x = leftMargin - textLayout.size.width - 6.dp.toPx(),
                        y = levelY - textLayout.size.height / 2
                    )
                )
            }

            if (readings.size > 1) {
                // 2. Draw Trend Line
                val path = Path()
                val minTime = readings.first().timestamp
                val maxTime = readings.last().timestamp
                val timeRange = (maxTime - minTime).toFloat()
                val scaleX = if (timeRange != 0f) plotWidth / timeRange else 1f

                for (i in readings.indices) {
                    val r = readings[i]
                    val valX = if (timeRange != 0f) {
                        leftMargin + (r.timestamp - minTime) * scaleX
                    } else {
                        leftMargin + plotWidth / 2
                    }

                    val v = if (selectedMetric == "HR") r.heartRateBpm else (r.respiratoryRate ?: 18).toDouble()
                    val valY = topMargin + plotHeight - ((v - adjustedMinY) / adjustedRangeY).toFloat() * plotHeight

                    if (i == 0) {
                        path.moveTo(valX, valY)
                    } else {
                        path.lineTo(valX, valY)
                    }

                    // Draw a small dot at each point
                    drawCircle(
                        color = accentColor,
                        radius = 3.dp.toPx(),
                        center = Offset(valX, valY)
                    )
                }

                drawPath(
                    path = path,
                    color = accentColor,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                )

                // 3. Draw X-Axis Labels (dates for first, middle, last points)
                val labelIndices = if (readings.size >= 3) {
                    listOf(0, readings.size / 2, readings.size - 1)
                } else {
                    listOf(0, readings.size - 1)
                }

                labelIndices.distinct().forEach { idx ->
                    val r = readings[idx]
                    val valX = if (timeRange != 0f) {
                        leftMargin + (r.timestamp - minTime) * scaleX
                    } else {
                        leftMargin + plotWidth / 2
                    }

                    val dateStr = dateFormatter.format(Date(r.timestamp))
                    val textLayout = textMeasurer.measure(
                        text = dateStr,
                        style = TextStyle(
                            fontFamily = IbmPlexMono,
                            fontSize = 10.sp,
                            color = textColor
                        )
                    )
                    drawText(
                        textLayoutResult = textLayout,
                        topLeft = Offset(
                            x = valX - textLayout.size.width / 2,
                            y = topMargin + plotHeight + 6.dp.toPx()
                        )
                    )
                }
            } else if (readings.size == 1) {
                // Draw a single dot in the center of the graph
                val r = readings.first()
                val valX = leftMargin + plotWidth / 2
                val v = if (selectedMetric == "HR") r.heartRateBpm else (r.respiratoryRate ?: 18).toDouble()
                val valY = topMargin + plotHeight - ((v - adjustedMinY) / adjustedRangeY).toFloat() * plotHeight

                drawCircle(
                    color = accentColor,
                    radius = 4.dp.toPx(),
                    center = Offset(valX, valY)
                )

                // Label for single date
                val dateStr = dateFormatter.format(Date(r.timestamp))
                val textLayout = textMeasurer.measure(
                    text = dateStr,
                    style = TextStyle(
                        fontFamily = IbmPlexMono,
                        fontSize = 10.sp,
                        color = textColor
                    )
                )
                drawText(
                    textLayoutResult = textLayout,
                    topLeft = Offset(
                        x = valX - textLayout.size.width / 2,
                        y = topMargin + plotHeight + 6.dp.toPx()
                    )
                )
            }
        }
    }
}

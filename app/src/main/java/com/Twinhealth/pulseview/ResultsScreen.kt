package com.Twinhealth.pulseview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

/**
 * Screen displaying the heart rate analysis results.
 * Fully formatted according to the web application visual identity,
 * including a toggleable power-user "Clinical View" detail section.
 */
@Composable
fun ResultsScreen(
    result: InferenceResult,
    respiratoryRate: Int = 18,
    onRecordAgain: () -> Unit = {},
    onDone: () -> Unit = {}
) {
    val heartRateBpm = result.heartRateBpm
    val signalQualityReliable = result.signalReliable
    val recordingDurationSeconds = result.recordingDurationSeconds
    val frameCount = result.frameCount

    var isExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Center Content Block (Scrollable to support expansion)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(24.dp))

            // Screen Title
            Text(
                text = "Analysis Results",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.textPrimary,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            // Large HR Display (uses DM Serif Display per web visual identity)
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = String.format("%.0f", heartRateBpm),
                    style = TextStyle(
                        fontFamily = DmSerifDisplay,
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "bpm",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.textMuted,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            // Quality Badge (flat with border and custom safe/warning color scheme, 4dp border radius)
            Box(
                modifier = Modifier
                    .background(
                        color = when (signalQualityReliable) {
                            true -> MaterialTheme.colorScheme.safe.copy(alpha = 0.15f)
                            false -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                            null -> MaterialTheme.colorScheme.warning.copy(alpha = 0.15f)
                        },
                        shape = RoundedCornerShape(4.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = when (signalQualityReliable) {
                            true -> MaterialTheme.colorScheme.safe
                            false -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.warning
                        },
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = when (signalQualityReliable) {
                        true -> "✓ Reliable Signal"
                        false -> "⚠ Check Signal"
                        null -> "⚠ Limited Signal"
                    },
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = IbmPlexSans
                    ),
                    color = when (signalQualityReliable) {
                        true -> MaterialTheme.colorScheme.safe
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.warning
                    },
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(48.dp))

            // Secondary Metrics section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Respiratory Rate Card
                MetricCard(
                    label = "Respiration",
                    value = "$respiratoryRate",
                    unit = "rpm",
                    modifier = Modifier.weight(1f)
                )
                // Recording Duration Card
                MetricCard(
                    label = "Duration",
                    value = String.format("%.1f", recordingDurationSeconds),
                    unit = "s",
                    modifier = Modifier.weight(1f)
                )
                // Frames Card
                MetricCard(
                    label = "Frames",
                    value = "$frameCount",
                    unit = "f",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(32.dp))

            // Expand / Collapse Toggle Button
            TextButton(
                onClick = { isExpanded = !isExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isExpanded) "HIDE CLINICAL DETAILS ▲" else "VIEW CLINICAL DETAILS ▼",
                    style = TextStyle(
                        fontFamily = IbmPlexSans,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.05.em
                    )
                )
            }

            // Expanded Clinical Details
            if (isExpanded) {
                Spacer(Modifier.height(24.dp))

                // ── 1. BVP Waveform Card ─────────────────────────────────────
                if (result.bvpSignal.isNotEmpty()) {
                    BvpWaveformCard(bvpSignal = result.bvpSignal)
                    Spacer(Modifier.height(16.dp))
                }

                // ── 2. Full Signal Metrics Table Card ────────────────────────
                SignalMetricsCard(result = result, respiratoryRate = respiratoryRate)
                Spacer(Modifier.height(16.dp))

                // ── 3. POS Quality Gate Table Card ───────────────────────────
                PosQualityGateCard(result = result)
                Spacer(Modifier.height(16.dp))

                // ── 4. Model Information Card ────────────────────────────────
                ModelInfoCard(result = result)
                
                Spacer(Modifier.height(24.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // Bottom Button Block (override shape with 4dp border radius)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = onRecordAgain,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(
                    text = "Record Again",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = IbmPlexSans
                    )
                )
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onDone,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.border),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.textPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(
                    text = "Done",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = IbmPlexSans
                    )
                )
            }
        }
    }
}

/**
 * Metric display card component.
 * Label uses IBM Plex Sans, Value uses IBM Plex Mono (technical/numeric readouts).
 */
@Composable
private fun MetricCard(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.card
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label.uppercase(),
                style = TextStyle(
                    fontFamily = IbmPlexSans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    letterSpacing = 0.07.em,
                    color = MaterialTheme.colorScheme.textMuted
                )
            )
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = value,
                    style = TextStyle(
                        fontFamily = IbmPlexMono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.textPrimary
                    )
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.textMuted,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

/**
 * Card displaying raw output BVP signal wave drawn dynamically.
 */
@Composable
fun BvpWaveformCard(bvpSignal: FloatArray) {
    Card(
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.card),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "BVP WAVEFORM",
                style = TextStyle(
                    fontFamily = IbmPlexSans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    letterSpacing = 0.07.em,
                    color = MaterialTheme.colorScheme.textMuted
                )
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(Color.Black.copy(alpha = 0.05f))
                    .border(1.dp, MaterialTheme.colorScheme.border.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 8.dp, vertical = 12.dp)
            ) {
                val accentColor = MaterialTheme.colorScheme.primary
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height

                    if (bvpSignal.size > 1) {
                        val minVal = bvpSignal.minOrNull() ?: 0f
                        val maxVal = bvpSignal.maxOrNull() ?: 1f
                        val range = maxVal - minVal
                        val scaleY = if (range != 0f) height / range else 1f

                        val path = androidx.compose.ui.graphics.Path()
                        val stepX = width / (bvpSignal.size - 1)

                        for (i in bvpSignal.indices) {
                            val x = i * stepX
                            val y = height - (bvpSignal[i] - minVal) * scaleY

                            if (i == 0) {
                                path.moveTo(x, y)
                            } else {
                                path.lineTo(x, y)
                            }
                        }

                        drawPath(
                            path = path,
                            color = accentColor,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 1.5.dp.toPx(),
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Card displaying a grid/table of full signal features, showing honest gaps as "—".
 */
@Composable
fun SignalMetricsCard(result: InferenceResult, respiratoryRate: Int) {
    Card(
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.card),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "FULL SIGNAL METRICS",
                style = TextStyle(
                    fontFamily = IbmPlexSans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    letterSpacing = 0.07.em,
                    color = MaterialTheme.colorScheme.textMuted
                )
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MetricRow(label = "Heart Rate (bpm)", value = "%.1f".format(result.heartRateBpm))
                MetricRow(label = "Respiratory Rate (brpm)", value = "$respiratoryRate")
                MetricRow(label = "IBI Mean (ms) (Experimental)", value = result.ibiMeanMs?.let { "%.1f".format(it) } ?: "—")
                MetricRow(label = "IBI Std (ms) (Experimental)", value = result.ibiStdMs?.let { "%.1f".format(it) } ?: "—")
                MetricRow(label = "RMSSD (ms) (Experimental)", value = result.rmssd?.let { "%.1f".format(it) } ?: "—")
                MetricRow(label = "SDNN (ms) (Experimental)", value = result.sdnn?.let { "%.1f".format(it) } ?: "—")
                MetricRow(label = "pNN50 (%) (Experimental)", value = result.pnn50?.let { "%.1f".format(it) } ?: "—")
                MetricRow(label = "APG b/a Ratio (Experimental)", value = result.apgBaRatio?.let { "%.3f".format(it) } ?: "—")
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = IbmPlexSans
            ),
            color = MaterialTheme.colorScheme.textMuted
        )
        Text(
            text = value,
            style = TextStyle(
                fontFamily = IbmPlexMono,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.textPrimary
            )
        )
    }
}

/**
 * Card displaying detailed comparative rows for corrected, POS estimation, divergence, and status.
 */
@Composable
fun PosQualityGateCard(result: InferenceResult) {
    Card(
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.card),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "POS QUALITY GATE",
                style = TextStyle(
                    fontFamily = IbmPlexSans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    letterSpacing = 0.07.em,
                    color = MaterialTheme.colorScheme.textMuted
                )
            )

            // Table Headers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "METRIC",
                    style = TextStyle(
                        fontFamily = IbmPlexSans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.textMuted
                    ),
                    modifier = Modifier.weight(1.1f)
                )
                TableHeaderCell("CORRECTED", Modifier.weight(1.2f))
                TableHeaderCell("POS REF", Modifier.weight(1.0f))
                TableHeaderCell("DIV", Modifier.weight(0.8f))
                TableHeaderCell("STATUS", Modifier.weight(1.3f))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.border)
            )

            // Table Row
            val correctedStr = "%.1f".format(result.heartRateBpm)
            val posStr = if (result.posHeartRateBpm != null) "%.1f".format(result.posHeartRateBpm) else "—"
            val divergence = if (result.posHeartRateBpm != null) {
                kotlin.math.abs(result.heartRateBpm - result.posHeartRateBpm)
            } else {
                null
            }
            val divStr = if (divergence != null) "%.1f".format(divergence) else "—"

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Heart Rate",
                    style = TextStyle(
                        fontFamily = IbmPlexSans,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.textPrimary
                    ),
                    modifier = Modifier.weight(1.1f)
                )
                TableCell(correctedStr, Modifier.weight(1.2f))
                TableCell(posStr, Modifier.weight(1.0f))
                TableCell(divStr, Modifier.weight(0.8f))
                SmallStatusBadge(result.signalReliable, Modifier.weight(1.3f))
            }
        }
    }
}

@Composable
private fun TableHeaderCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = TextStyle(
            fontFamily = IbmPlexSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.textMuted
        ),
        modifier = modifier,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun TableCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = TextStyle(
            fontFamily = IbmPlexMono,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.textPrimary
        ),
        modifier = modifier,
        textAlign = TextAlign.Center
    )
}

@Composable
fun SmallStatusBadge(signalQualityReliable: Boolean?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                color = when (signalQualityReliable) {
                    true -> MaterialTheme.colorScheme.safe.copy(alpha = 0.15f)
                    false -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                    null -> MaterialTheme.colorScheme.warning.copy(alpha = 0.15f)
                },
                shape = RoundedCornerShape(2.dp)
            )
            .border(
                width = 1.dp,
                color = when (signalQualityReliable) {
                    true -> MaterialTheme.colorScheme.safe
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.warning
                },
                shape = RoundedCornerShape(2.dp)
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = when (signalQualityReliable) {
                true -> "RELIABLE"
                false -> "UNRELIABLE"
                null -> "LIMITED"
            },
            style = TextStyle(
                fontFamily = IbmPlexSans,
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp,
                color = when (signalQualityReliable) {
                    true -> MaterialTheme.colorScheme.safe
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.warning
                }
            )
        )
    }
}

/**
 * Card displaying ONNX model details.
 */
@Composable
fun ModelInfoCard(result: InferenceResult) {
    Card(
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.card),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "MODEL INFORMATION",
                style = TextStyle(
                    fontFamily = IbmPlexSans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    letterSpacing = 0.07.em,
                    color = MaterialTheme.colorScheme.textMuted
                )
            )

            val inputFormat = "21 frames · %.1f Hz · 72×72 px".format(result.achievedFps)

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModelInfoRow(label = "Model Name", value = "EfficientPhys, WACV 2023")
                ModelInfoRow(label = "Input Format", value = inputFormat)
                ModelInfoRow(label = "Validation", value = "Cross-validated against reference wearable devices")
            }
        }
    }
}

@Composable
private fun ModelInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = IbmPlexSans
            ),
            color = MaterialTheme.colorScheme.textMuted,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = TextStyle(
                fontFamily = IbmPlexSans,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.textPrimary
            ),
            modifier = Modifier.weight(2f),
            textAlign = TextAlign.End
        )
    }
}

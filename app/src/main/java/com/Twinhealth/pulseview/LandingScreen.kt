package com.Twinhealth.pulseview

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.Twinhealth.pulseview.ui.theme.*

@Composable
fun LandingScreen(
    onBeginScan: () -> Unit
) {
    val context = LocalContext.current
    val isDark = ThemeSettings.isDarkTheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // ── Hero Section ─────────────────────────────────────────────────────
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Your heart, seen through your face.",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.textPrimary,
                lineHeight = 38.sp
            )

            Text(
                text = "PulseView uses remote photoplethysmography (rPPG) to analyze micro-color changes in your skin. By tracking these subtle blood flow variations, our neural network calculates your heart rate in real time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.textMuted,
                lineHeight = 22.sp
            )

            Button(
                onClick = onBeginScan,
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "BEGIN SCAN",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = IbmPlexSans
                        )
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // ── How It Works Section ─────────────────────────────────────────────
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "HOW IT WORKS",
                style = TextStyle(
                    fontFamily = IbmPlexSans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    letterSpacing = 0.07.em,
                    color = MaterialTheme.colorScheme.textMuted
                )
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StepCard(
                    number = "01",
                    icon = Icons.Default.PlayArrow,
                    title = "Record",
                    description = "Position your face in the camera frame and stay completely still for 25 seconds."
                )
                StepCard(
                    number = "02",
                    icon = Icons.Default.Settings,
                    title = "Analyze",
                    description = "Our neural network detects your face and runs ONNX inference on raw skin color signals."
                )
                StepCard(
                    number = "03",
                    icon = Icons.Default.Check,
                    title = "Results",
                    description = "View your live heart rate, validated and corrected using quality-gated POS filters."
                )
            }
        }

        // ── Checklist Section ────────────────────────────────────────────────
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "WHAT YOU NEED",
                style = TextStyle(
                    fontFamily = IbmPlexSans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    letterSpacing = 0.07.em,
                    color = MaterialTheme.colorScheme.textMuted
                )
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ChecklistItem(text = "Good, stable room lighting (avoid backlighting)")
                ChecklistItem(text = "Hold the camera steady or rest the phone")
                ChecklistItem(text = "Face the front camera directly")
                ChecklistItem(text = "Stay still and silent during the scan")
                ChecklistItem(text = "Keep face clean of extreme angles/shadows")
            }
        }

        // ── Disclaimer Box ───────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.warning.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(4.dp)
                )
                .height(IntrinsicSize.Min)
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.warning)
            )
            Column(
                modifier = Modifier
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "IMPORTANT NOTICE",
                    style = TextStyle(
                        fontFamily = IbmPlexSans,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 0.07.em,
                        color = MaterialTheme.colorScheme.warning
                    )
                )
                Text(
                    text = "This application is not intended for medical diagnosis, clinical use, or treatment. It is for informational and educational references only. Always consult a healthcare professional for accurate assessments.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.textPrimary,
                    lineHeight = 20.sp
                )
            }
        }
    }
}


@Composable
private fun StepCard(
    number: String,
    icon: ImageVector,
    title: String,
    description: String
) {
    Card(
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.card
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = number,
                    style = TextStyle(
                        fontFamily = IbmPlexMono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.textPrimary
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.textMuted,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun ChecklistItem(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.textPrimary
        )
    }
}

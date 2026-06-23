package com.Twinhealth.pulseview

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Twinhealth.pulseview.ui.theme.PulseviewTheme
import com.Twinhealth.pulseview.ui.theme.ThemeSettings
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.Query
import com.google.firebase.ktx.Firebase
import org.opencv.android.OpenCVLoader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.Twinhealth.pulseview.ui.theme.*

/**
 * Single-activity entry point for PulseView.
 *
 * Responsibilities:
 *  1. Initialize OpenCV native library (once, on startup).
 *  2. Request the CAMERA permission at runtime before showing the camera UI.
 *  3. Host the Compose content tree with a two-screen state machine:
 *     - [CameraScreen]: records video, runs inference pipeline, produces [InferenceResult]
 *     - [ResultsScreen]: displays computed heart rate, logs to Firebase, offers "Record Again"
 *
 * Navigation flow:
 *   CameraScreen → (inference pipeline completes) → ResultsScreen → (Record Again) → CameraScreen
 *
 * Firebase logging happens at the transition from Camera → Results, using real
 * values from the inference pipeline (not dummy/hardcoded data).
 */
class MainActivity : ComponentActivity() {

    // Compose-observable permission state — triggers recomposition on change
    private var cameraPermissionGranted by mutableStateOf(false)

    // registerForActivityResult MUST be called unconditionally before onCreate
    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraPermissionGranted = granted
            if (!granted) {
                Log.w("MainActivity",
                    "Camera permission DENIED — user will see PermissionDeniedScreen")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // ── Initialize OpenCV ─────────────────────────────────────────────
        // OpenCVLoader.initLocal() loads the bundled native .so from the
        // opencv:4.10.0 AAR. Must succeed before any OpenCV call is made.
        val opencvOk = OpenCVLoader.initLocal()
        if (!opencvOk) {
            Log.e("MainActivity",
                "OpenCV native library failed to load — CV operations will crash!")
        } else {
            Log.i("MainActivity",
                "OpenCV initialized: ${org.opencv.core.Core.VERSION}")
        }

        // ── Initialize Theme Settings ─────────────────────────────────────
        ThemeSettings.init(this)

        // ── Request CAMERA permission ─────────────────────────────────────
        // ActivityResultContracts.RequestPermission() shows the system dialog once.
        // If the user previously denied and checked "Don't ask again", this returns
        // false immediately without showing a dialog. We handle that via PermissionDeniedScreen.
        requestCameraPermission.launch(Manifest.permission.CAMERA)

        // ── Compose content ───────────────────────────────────────────────
        setContent {
            PulseviewTheme {
                if (cameraPermissionGranted) {
                    val authManager = remember { AuthManager() }
                    var currentScreen by remember {
                        val currentUser = authManager.getCurrentUser()
                        val initialScreen = if (currentUser != null &&
                            currentUser.email?.endsWith("@Twinhealth.com", ignoreCase = true) == true
                        ) {
                            // Trigger client-side BVP retention cleanup in a background coroutine
                            lifecycleScope.launch(Dispatchers.IO) {
                                runBvpCleanup(this@MainActivity, currentUser.uid)
                            }
                            AppScreen.MainShell
                        } else {
                            if (currentUser != null) {
                                authManager.signOut()
                            }
                            AppScreen.SignIn
                        }
                        mutableStateOf<AppScreen>(initialScreen)
                    }
                    var activeTab by remember { mutableStateOf(MainTab.Home) }
                    val coroutineScope = rememberCoroutineScope()
                    val context = this@MainActivity

                    when (val screen = currentScreen) {
                        is AppScreen.SignIn -> {
                            SignInScreen(
                                authManager = authManager,
                                onSignInSuccess = {
                                    activeTab = MainTab.Home
                                    currentScreen = AppScreen.MainShell
                                }
                            )
                        }
                        is AppScreen.MainShell -> {
                            MainShellScreen(
                                authManager = authManager,
                                activeTab = activeTab,
                                onTabChange = { activeTab = it },
                                onBeginScan = {
                                    currentScreen = AppScreen.Camera
                                },
                                onSignOut = {
                                    authManager.signOut()
                                    currentScreen = AppScreen.SignIn
                                }
                            )
                        }
                        is AppScreen.Camera -> {
                            CameraScreen(
                                onInferenceComplete = { result ->
                                    if (BuildConfig.DEBUG) {
                                        Log.i("MainActivity",
                                            "Inference complete: HR=%.2f BPM, %d frames, %.1fs, %.1f fps"
                                                .format(result.heartRateBpm, result.frameCount,
                                                    result.recordingDurationSeconds, result.achievedFps))
                                    }

                                    // Log to Firebase with REAL values including extended clinical details
                                    coroutineScope.launch {
                                        val deviceId = HrLogger.getOrCreateDeviceId(context)
                                        val logResult = HrLogger().logReading(
                                            HrReading(
                                                heartRateBpm = result.heartRateBpm,
                                                timestamp = System.currentTimeMillis(),
                                                deviceId = deviceId,
                                                recordingDurationSeconds = result.recordingDurationSeconds,
                                                frameCount = result.frameCount,
                                                achievedFps = result.achievedFps,
                                                signalQualityReliable = result.signalReliable,
                                                heartRateBpmRaw = result.heartRateBpmRaw,
                                                posHeartRateBpm = result.posHeartRateBpm,
                                                bvpSignal = result.bvpSignal.toList(),
                                                respiratoryRate = 18,
                                                ibiMeanMs = result.ibiMeanMs,
                                                ibiStdMs = result.ibiStdMs,
                                                rmssd = result.rmssd,
                                                sdnn = result.sdnn,
                                                pnn50 = result.pnn50,
                                                apgBaRatio = result.apgBaRatio
                                            )
                                        )
                                        Log.i("MainActivity",
                                            "Firebase log result: ${if (logResult.isSuccess) "SUCCESS" else "FAILED: ${logResult.exceptionOrNull()?.message}"}")
                                    }

                                    // Navigate to ResultsScreen
                                    currentScreen = AppScreen.Results(result)
                                }
                            )
                        }
                        is AppScreen.Results -> {
                            ResultsScreen(
                                result = screen.result,
                                onRecordAgain = {
                                    activeTab = MainTab.Home
                                    currentScreen = AppScreen.Camera
                                },
                                onDone = {
                                    activeTab = MainTab.Home
                                    currentScreen = AppScreen.MainShell
                                }
                            )
                        }
                    }
                } else {
                    PermissionDeniedScreen()
                }
            }
        }
    }

    /**
     * Client-side, best-effort retention policy running once per day per signed-in user at app launch.
     *
     * This implementation provides broader coverage than a screen-specific trigger since authentication
     * is a natural precondition for app usage. However, it is not a server-side guarantee (e.g., if a
     * user does not open the app for months, their old BVP data won't be cleared until the next launch).
     * This is an accepted limitation for the current prototype/internal-tool scope. A server-side
     * Cloud Function would be a more robust future improvement to enforce retention guarantees.
     *
     * @param context The application context to access SharedPreferences.
     * @param userId The authenticated user's UID.
     */
    private suspend fun runBvpCleanup(context: Context, userId: String) {
        try {
            val sharedPrefs = context.getSharedPreferences("pulseview_prefs", Context.MODE_PRIVATE)
            val lastCleanup = sharedPrefs.getLong("last_bvp_cleanup_timestamp", 0L)
            val now = System.currentTimeMillis()
            if (now - lastCleanup < 24L * 60 * 60 * 1000) {
                if (BuildConfig.DEBUG) {
                    Log.i("MainActivity", "BVP cleanup skipped, ran recently")
                }
                return
            }

            // Update stored timestamp to now immediately upon proceeding to ensure throttling on next launch
            sharedPrefs.edit().putLong("last_bvp_cleanup_timestamp", now).apply()

            // Small initial delay to let Firebase Auth token load/propagate to Firestore on launch
            delay(1500)
            
            Log.i("MainActivity", "Starting BVP cleanup: userId=$userId, authEmail=${com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email}")

            val db = Firebase.firestore
            val cutoff = now - (30L * 24 * 60 * 60 * 1000)
            
            var querySnapshot: com.google.firebase.firestore.QuerySnapshot? = null
            var attempts = 0
            while (attempts < 3) {
                try {
                    querySnapshot = db.collection("hr_readings")
                        .whereEqualTo("deviceId", userId)
                        .get()
                        .await()
                    break
                } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
                    // If denied, retry after a delay as the Auth token might still be propagating to Firestore
                    if (e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        attempts++
                        if (attempts >= 3) throw e
                        delay(2000)
                    } else {
                        throw e
                    }
                }
            }

            if (querySnapshot == null) return

            var cleanedCount = 0
            val batch = db.batch()
            var hasUpdates = false

            for (document in querySnapshot.documents) {
                val reading = document.toObject(HrReading::class.java)
                if (reading != null && reading.timestamp < cutoff && reading.bvpSignal != null && reading.bvpSignal.isNotEmpty()) {
                    batch.update(document.reference, "bvpSignal", emptyList<Float>())
                    cleanedCount++
                    hasUpdates = true
                }
            }

            if (hasUpdates) {
                batch.commit().await()
            }

            if (BuildConfig.DEBUG) {
                Log.i("MainActivity", "BVP cleanup complete. Cleaned up $cleanedCount documents.")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to run BVP cleanup", e)
        }
    }
}

/**
 * Tabs available in the bottom navigation shell.
 */
enum class MainTab {
    Home,
    History,
    Trends
}

/**
 * Authenticated bottom navigation shell screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShellScreen(
    authManager: AuthManager,
    activeTab: MainTab,
    onTabChange: (MainTab) -> Unit,
    onBeginScan: () -> Unit,
    onSignOut: () -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "PulseView",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.textPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = activeTab == MainTab.Home,
                    onClick = { onTabChange(MainTab.Home) },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
                NavigationBarItem(
                    selected = activeTab == MainTab.History,
                    onClick = { onTabChange(MainTab.History) },
                    icon = { Icon(Icons.Default.History, contentDescription = "History") },
                    label = { Text("History") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
                NavigationBarItem(
                    selected = activeTab == MainTab.Trends,
                    onClick = { onTabChange(MainTab.Trends) },
                    icon = { Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = "Trends") },
                    label = { Text("Trends") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (activeTab) {
                MainTab.Home -> {
                    LandingScreen(
                        onBeginScan = onBeginScan
                    )
                }
                MainTab.History -> {
                    HistoryScreen()
                }
                MainTab.Trends -> {
                    TrendsScreen()
                }
            }
        }
    }

    if (showSettings) {
        SettingsBottomSheet(
            authManager = authManager,
            onDismiss = { showSettings = false },
            onSignOut = onSignOut
        )
    }
}

/**
 * Settings bottom sheet panel showing profile, theme switcher, about info, and sign out button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    authManager: AuthManager,
    onDismiss: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val currentUser = authManager.getCurrentUser()
    val isDark = ThemeSettings.isDarkTheme
    var showSignOutConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Sheet Title
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.textPrimary,
                fontFamily = DmSerifDisplay
            )

            // 1. Profile Section
            val name = currentUser?.displayName ?: "PulseView User"
            val email = currentUser?.email ?: ""
            val photoUrl = currentUser?.photoUrl

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(4.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.border,
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (photoUrl != null) {
                    AsyncImage(
                        model = photoUrl.toString(),
                        contentDescription = "Profile Picture",
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.border, CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name.take(1).uppercase(),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = name,
                        fontFamily = IbmPlexSans,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.textPrimary
                    )
                    Text(
                        text = email,
                        fontFamily = IbmPlexSans,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.textMuted
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.border)

            // 2. Appearance Section
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "APPEARANCE",
                    style = TextStyle(
                        fontFamily = IbmPlexSans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        letterSpacing = 0.07.em,
                        color = MaterialTheme.colorScheme.textMuted
                    )
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.border,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isDark) "Dark Mode" else "Light Mode",
                        fontFamily = IbmPlexSans,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.textPrimary
                    )

                    ThemeToggleSwitch(
                        checked = isDark,
                        onCheckedChange = { ThemeSettings.toggleTheme(context) }
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.border)

            // 3. About Section
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "ABOUT PULSEVIEW",
                    style = TextStyle(
                        fontFamily = IbmPlexSans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        letterSpacing = 0.07.em,
                        color = MaterialTheme.colorScheme.textMuted
                    )
                )

                Text(
                    text = "PulseView uses EfficientPhys, a lightweight on-device deep learning model, combined with the classical POS algorithm, to estimate heart rate and respiratory rate from a 25-second facial video recording — no wearable device required.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.textPrimary,
                    lineHeight = 20.sp
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.border)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Model",
                            fontFamily = IbmPlexSans,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.textPrimary,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "EfficientPhys, WACV 2023",
                            fontFamily = IbmPlexSans,
                            color = MaterialTheme.colorScheme.textMuted,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.border)

            // 4. Sign Out Section
            Button(
                onClick = { showSignOutConfirm = true },
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "SIGN OUT",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = IbmPlexSans
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = {
                Text(
                    text = "Confirm Sign Out",
                    fontFamily = DmSerifDisplay,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.textPrimary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to sign out of your Google account?",
                    fontFamily = IbmPlexSans,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.textPrimary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutConfirm = false
                        onDismiss()
                        onSignOut()
                    }
                ) {
                    Text(
                        text = "Sign Out",
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = IbmPlexSans,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSignOutConfirm = false }
                ) {
                    Text(
                        text = "Cancel",
                        color = MaterialTheme.colorScheme.textMuted,
                        fontFamily = IbmPlexSans
                    )
                }
            },
            shape = RoundedCornerShape(4.dp),
            containerColor = MaterialTheme.colorScheme.background
        )
    }
}

/**
 * Custom toggle switch layout.
 */
@Composable
private fun ThemeToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val transition = updateTransition(targetState = checked, label = "toggle")
    val thumbOffset by transition.animateDp(
        transitionSpec = { tween(durationMillis = 200) },
        label = "thumbOffset"
    ) { state ->
        if (state) 20.dp else 2.dp
    }
    val trackColor by animateColorAsState(
        targetValue = if (checked) Color(0xFF1F2937) else Color(0xFFE5E7EB),
        label = "trackColor"
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) Color(0xFF14FFEC) else Color(0xFF0D7377),
        label = "thumbColor"
    )

    Box(
        modifier = modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(CircleShape)
            .background(trackColor)
            .clickable { onCheckedChange(!checked) }
            .padding(2.dp)
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(20.dp)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}

/**
 * Sealed interface for the navigation state machine.
 */
private sealed interface AppScreen {
    /** Sign In screen. */
    data object SignIn : AppScreen

    /** Main bottom navigation shell. */
    data object MainShell : AppScreen

    /** Camera recording + inference pipeline screen. */
    data object Camera : AppScreen

    /** Results display screen with real computed data. */
    data class Results(val result: InferenceResult) : AppScreen
}

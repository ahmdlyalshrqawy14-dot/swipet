package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle possible sharing intents upon first launch
        handleIncomingIntent(intent)

        setContent {
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("swipe_prefs", Context.MODE_PRIVATE) }
            val sysLang = java.util.Locale.getDefault().language
            val defaultLang = if (sysLang == "ar") "ar" else "en"
            var currentLanguage by remember { mutableStateOf(prefs.getString("selected_lang", defaultLang) ?: defaultLang) }

            // Update configuration in-place so that resources use the correct language
            // while preserving the original ComponentActivity context instance.
            remember(currentLanguage) {
                val locale = java.util.Locale(currentLanguage)
                java.util.Locale.setDefault(locale)
                val config = context.resources.configuration
                config.setLocale(locale)
                config.setLayoutDirection(locale)
                @Suppress("DEPRECATION")
                context.resources.updateConfiguration(config, context.resources.displayMetrics)
            }
            val layoutDirection = if (currentLanguage == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr

            MyApplicationTheme {
                CompositionLocalProvider(
                    LocalLayoutDirection provides layoutDirection
                ) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = MaterialTheme.colorScheme.background
                    ) { innerPadding ->
                        SwipeAppContent(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            activity = this@MainActivity,
                            currentLanguage = currentLanguage,
                            onLanguageChanged = { newLang ->
                                prefs.edit().putString("selected_lang", newLang).apply()
                                currentLanguage = newLang
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        val uris = mutableListOf<Uri>()

        if (Intent.ACTION_SEND == action && type != null) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                uris.add(uri)
            } ?: intent.clipData?.let { clipData ->
                if (clipData.itemCount > 0) {
                    clipData.getItemAt(0).uri?.let { uri ->
                        uris.add(uri)
                    }
                }
            }
        } else if (Intent.ACTION_SEND_MULTIPLE == action && type != null) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { list ->
                uris.addAll(list)
            }
        }

        if (uris.isNotEmpty()) {
            Log.d(TAG, "Received shared files via share sheet: ${uris.size} URIs")
            // Automatically launch sender flow
            TransferManager.startSender(this, uris)
            Toast.makeText(this, getString(R.string.toast_share_received, uris.size), Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeAppContent(
    modifier: Modifier = Modifier,
    activity: MainActivity,
    currentLanguage: String,
    onLanguageChanged: (String) -> Unit
) {
    val context = LocalContext.current
    val transferState by TransferManager.state.collectAsStateWithLifecycle()
    val prefs = remember { context.getSharedPreferences("swipe_prefs", Context.MODE_PRIVATE) }

    var showCodeInputDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showHistoryScreen by remember { mutableStateOf(false) }
    var showTextInputDialog by remember { mutableStateOf(false) }
    var textShareInput by remember { mutableStateOf("") }
    var inputCode by remember { mutableStateOf("") }
    var useManualIp by remember { mutableStateOf(false) }
    var manualIp by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("8283") }
    
    // Track permissions
    var hasPermissions by remember { mutableStateOf(hasStoragePermission(context)) }
    var isWifiConnected by remember { mutableStateOf(isConnectedToWifi(context)) }

    val coroutineScope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val historyList by db.transferHistoryDao().getAllHistory().collectAsStateWithLifecycle(initialValue = emptyList())

    var pendingTransferAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val requestNotificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        pendingTransferAction?.invoke()
        pendingTransferAction = null
    }

    val runWithNotificationPermissionCheck = { action: () -> Unit ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionStatus = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            )
            if (permissionStatus == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                action()
            } else {
                pendingTransferAction = action
                requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            action()
        }
    }

    // Nearby Wi-Fi Devices permission: on Android 13+ some OEM builds gate local network
    // advertising/discovery (used to let the other device find & connect to this one) behind
    // the "Nearby devices" permission group. Requesting it upfront avoids a silent failure
    // where the sender waits forever without ever being discoverable.
    var pendingNearbyWifiAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val requestNearbyWifiPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        pendingNearbyWifiAction?.invoke()
        pendingNearbyWifiAction = null
    }

    val runWithNearbyWifiPermissionCheck = { action: () -> Unit ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionStatus = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.NEARBY_WIFI_DEVICES
            )
            if (permissionStatus == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                action()
            } else {
                pendingNearbyWifiAction = action
                requestNearbyWifiPermissionLauncher.launch(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        } else {
            action()
        }
    }

    // Combined check used right before starting a transfer: nearby-wifi permission first,
    // then notifications, then the actual action. Kept as one helper so every send entry
    // point goes through the exact same, isolated permission flow.
    val runWithTransferPermissionsCheck = { action: () -> Unit ->
        runWithNearbyWifiPermissionCheck {
            runWithNotificationPermissionCheck(action)
        }
    }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            runWithTransferPermissionsCheck {
                TransferManager.startSender(context, uris)
            }
        }
    }

    // Folder tree picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runWithTransferPermissionsCheck {
                coroutineScope.launch(Dispatchers.IO) {
                    val documentFile = DocumentFile.fromTreeUri(context, uri)
                    if (documentFile != null) {
                        val list = mutableListOf<UriWithPath>()
                        collectFilesFromTree(context, documentFile, documentFile.name ?: "Folder", list)
                        if (list.isNotEmpty()) {
                            val uris = list.map { it.uri }
                            val paths = list.map { it.relativePath }
                            TransferManager.startSender(context, uris, paths)
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, context.getString(R.string.toast_folder_empty), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }

    // Check permissions onResume to prevent background CPU usage
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasPermissions = hasStoragePermission(context)
                isWifiConnected = isConnectedToWifi(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        if (showHistoryScreen && transferState is TransferState.Idle) {
            HistoryScreen(
                historyList = historyList,
                onClearAll = {
                    coroutineScope.launch {
                        db.transferHistoryDao().clearHistory()
                    }
                },
                onBack = { showHistoryScreen = false }
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                when (val state = transferState) {
                    is TransferState.Idle -> {
                        IdleHomeScreen(
                            hasPermissions = hasPermissions,
                            isWifiConnected = isWifiConnected,
                            onGrantPermissions = {
                                requestStoragePermission(activity)
                            },
                            onSendClick = {
                                filePickerLauncher.launch("*/*")
                            },
                            onSendFolderClick = {
                                folderPickerLauncher.launch(null)
                            },
                            onSendTextClick = {
                                showTextInputDialog = true
                            },
                            onReceiveClick = {
                                showCodeInputDialog = true
                            },
                            onSettingsClick = {
                                showSettingsDialog = true
                            },
                            onHistoryClick = {
                                showHistoryScreen = true
                            }
                        )
                    }
                    is TransferState.SenderWaiting -> {
                        SenderWaitingScreen(
                            state = state,
                            onCancel = {
                                TransferManager.cancelTransfer()
                            }
                        )
                    }
                    is TransferState.SenderWaitingForApproval -> {
                        SenderApprovalScreen(
                            state = state,
                            onCancel = {
                                TransferManager.cancelTransfer()
                            }
                        )
                    }
                    is TransferState.ReceiverConnecting -> {
                        ReceiverConnectingScreen(
                            state = state,
                            onCancel = {
                                TransferManager.cancelTransfer()
                            }
                        )
                    }
                    is TransferState.ActiveTransfer -> {
                        ActiveTransferScreen(
                            state = state,
                            onCancel = {
                                TransferManager.cancelTransfer()
                            }
                        )
                    }
                    is TransferState.Finished -> {
                        FinishedSummaryScreen(
                            state = state,
                            onClose = {
                                ReceivedTextHolder.lastReceivedText = null
                                TransferManager.cleanup()
                                TransferManager.cancelTransfer()
                            },
                            onManualIpClick = {
                                ReceivedTextHolder.lastReceivedText = null
                                TransferManager.cleanup()
                                TransferManager.cancelTransfer()
                                showCodeInputDialog = true
                                useManualIp = true
                            }
                        )
                    }
                }

                if (transferState !is TransferState.Idle && transferState !is TransferState.Finished) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                    ) {
                        NotificationPermissionWarningBanner(context)
                    }
                }
            }
        }

        // 1. Code Input Dialog for Receiver
        if (showCodeInputDialog) {
            val isPortValid = remember(manualPort) {
                val portInt = manualPort.toIntOrNull()
                portInt != null && portInt in 1..65535
            }
            val isPortError = remember(manualPort) {
                manualPort.isNotEmpty() && (manualPort.toIntOrNull() == null || manualPort.toIntOrNull()!! !in 1..65535)
            }
            val isConfirmEnabled = if (useManualIp) {
                manualIp.isNotBlank() && isPortValid
            } else {
                inputCode.length == 4
            }

            AlertDialog(
                onDismissRequest = { 
                    showCodeInputDialog = false
                    useManualIp = false
                },
                title = {
                    Text(
                        text = if (useManualIp) stringResource(R.string.btn_connect_manual) else stringResource(R.string.code_dialog_title),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (!useManualIp) {
                            Text(
                                text = stringResource(R.string.code_dialog_placeholder),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )

                            // Force Pin input to remain LTR regardless of selected app language
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                OutlinedTextField(
                                    value = inputCode,
                                    onValueChange = {
                                        if (it.length <= 4 && it.all { char -> char.isDigit() }) {
                                            inputCode = it
                                        }
                                    },
                                    placeholder = { Text("0000") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    textStyle = LocalTextStyle.current.copy(
                                        textAlign = TextAlign.Center,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 4.sp
                                    ),
                                    modifier = Modifier
                                        .width(180.dp)
                                        .testTag("pin_code_input"),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.card_waiting_desc),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )

                            // Force Manual IP/Port entry to remain LTR regardless of selected app language
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = manualIp,
                                        onValueChange = { manualIp = it },
                                        label = { Text(stringResource(R.string.input_ip_label)) },
                                        placeholder = { Text(stringResource(R.string.input_ip_placeholder)) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp)
                                            .testTag("manual_ip_input"),
                                        shape = RoundedCornerShape(12.dp)
                                    )

                                    OutlinedTextField(
                                        value = manualPort,
                                        onValueChange = { manualPort = it },
                                        label = { Text(stringResource(R.string.manual_port_label)) },
                                        placeholder = { Text(stringResource(R.string.input_port_placeholder)) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        isError = isPortError,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("manual_port_input"),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    if (isPortError) {
                                        Text(
                                            text = stringResource(R.string.port_invalid_error),
                                            color = MaterialTheme.colorScheme.error,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        TextButton(
                            onClick = { useManualIp = !useManualIp }
                        ) {
                            Text(
                                text = if (useManualIp) stringResource(R.string.btn_back) else stringResource(R.string.btn_connect_manual),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (useManualIp) {
                                if (manualIp.isNotBlank() && isPortValid) {
                                    val portInt = manualPort.toIntOrNull() ?: 8283
                                    showCodeInputDialog = false
                                    val ipToUse = manualIp.trim()
                                    val portToUse = portInt
                                    // Reset manual inputs
                                    manualIp = ""
                                    manualPort = "8283"
                                    useManualIp = false
                                    runWithNotificationPermissionCheck {
                                        TransferManager.startReceiver(context, "", ipToUse, portToUse)
                                    }
                                }
                            } else {
                                if (inputCode.length == 4) {
                                    showCodeInputDialog = false
                                    val codeToUse = inputCode
                                    inputCode = ""
                                    runWithNotificationPermissionCheck {
                                        TransferManager.startReceiver(context, codeToUse)
                                    }
                                }
                            }
                        },
                        enabled = isConfirmEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("submit_receive_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(stringResource(R.string.btn_connect), color = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { 
                            showCodeInputDialog = false
                            useManualIp = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.btn_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp)
            )
        }

        // 2. Settings Dialog
        if (showSettingsDialog) {
            SettingsFlowDialog(
                onDismiss = { showSettingsDialog = false },
                onResetConfirmed = {
                    resetApp(context)
                    showSettingsDialog = false
                    Toast.makeText(context, context.getString(R.string.toast_reset_success), Toast.LENGTH_LONG).show()
                },
                currentLanguage = currentLanguage,
                onLanguageChanged = onLanguageChanged
            )
        }

        // 3. Quick Text Share Dialog
        if (showTextInputDialog) {
            AlertDialog(
                onDismissRequest = { showTextInputDialog = false },
                title = {
                    Text(
                        text = stringResource(R.string.text_share_dialog_title),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.text_share_desc),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        OutlinedTextField(
                            value = textShareInput,
                            onValueChange = { textShareInput = it },
                            placeholder = { Text(stringResource(R.string.text_share_placeholder_input)) },
                            singleLine = false,
                            maxLines = 4,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .testTag("text_share_input"),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (textShareInput.isNotBlank()) {
                                showTextInputDialog = false
                                val textToShare = textShareInput
                                textShareInput = ""
                                runWithTransferPermissionsCheck {
                                    TransferManager.startSender(
                                        context,
                                        listOf(Uri.parse("text://share")),
                                        listOf(textToShare)
                                    )
                                }
                            } else {
                                Toast.makeText(context, context.getString(R.string.text_share_error_empty), Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("submit_text_share_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(stringResource(R.string.text_share_btn_start), color = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showTextInputDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.btn_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}

// --- SCREEN LAYOUTS ---

enum class HomeScreenState {
    MAIN,
    SEND_PREPARE
}

@Composable
fun Modifier.bounceClick(onClick: () -> Unit): Modifier {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "bounce"
    )
    val context = LocalContext.current

    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    isPressed = true
                    try {
                        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(12, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator?.vibrate(12)
                        }
                    } catch (e: Exception) {}
                    tryAwaitRelease()
                    isPressed = false
                },
                onTap = { onClick() }
            )
        }
}

@Composable
fun Modifier.pulseEffect(): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun RotatingGreeting(modifier: Modifier = Modifier) {
    val greetings = listOf(
        "مرحباً بك في سوايب",
        "Welcome to Swipe",
        "Добро пожаловать в Свайп",
        "欢迎来到斯怀普",
        "Bienvenue sur Swipe",
        "Te damos la bienvenida a Swipe",
        "Willkommen bei Swipe",
        "スワイプへようこそ",
        "Swipe'a hoş geldiniz",
        "स्वाइप में आपका स्वागत है"
    )
    var currentIndex by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(7000)
            currentIndex = (currentIndex + 1) % greetings.size
        }
    }

    Crossfade(
        targetState = currentIndex,
        animationSpec = tween(durationMillis = 800),
        modifier = modifier,
        label = "greeting_fade"
    ) { index ->
        Text(
            text = greetings[index],
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun IdleHomeScreen(
    hasPermissions: Boolean,
    isWifiConnected: Boolean,
    onGrantPermissions: () -> Unit,
    onSendClick: () -> Unit = {},
    onSendFolderClick: () -> Unit = {},
    onSendTextClick: () -> Unit = {},
    onReceiveClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onHistoryClick: () -> Unit = {}
) {
    val context = LocalContext.current
    var screenState by remember { mutableStateOf(HomeScreenState.MAIN) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (screenState == HomeScreenState.MAIN) {
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0x1AFFFFFF))
                        .testTag("settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.settings_title),
                        tint = Color.White
                    )
                }
            } else {
                IconButton(
                    onClick = { screenState = HomeScreenState.MAIN },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0x1AFFFFFF))
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.btn_back),
                        tint = Color.White
                    )
                }
            }

            Text(
                text = "Swipe",
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFF3D8BFF) // Neon Blue primary branding
            )

            if (screenState == HomeScreenState.MAIN) {
                IconButton(
                    onClick = onHistoryClick,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0x1AFFFFFF))
                        .testTag("history_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = stringResource(R.string.history_title),
                        tint = Color.White
                    )
                }
            } else {
                Box(modifier = Modifier.size(48.dp))
            }
        }

        if (screenState == HomeScreenState.MAIN) {
            // Main Home Screen Contents
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                // Multilingual Rotating Greeting
                RotatingGreeting(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp)
                )

                // Elegant glowing Rocket Fist Logo
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .clip(RoundedCornerShape(36.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF131528),
                                    Color(0xFF1A1035)
                                )
                            )
                        )
                        .border(
                            width = 1.5.dp,
                            brush = Brush.verticalGradient(
                                colors = listOf(Color(0xFF3D8BFF), Color(0xFF2ECC91))
                            ),
                            shape = RoundedCornerShape(36.dp)
                        )
                        .padding(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "Swipe Logo",
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = stringResource(R.string.home_title_desc),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.home_subtitle_desc),
                    fontSize = 14.sp,
                    color = Color(0xFF9EACBC),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                if (!isWifiConnected) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.WifiOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.no_wifi_warning),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Inform where files are saved (with Glassmorphism styling)
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x16FFFFFF)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0x1AFFFFFF))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.desc_folder_location),
                            tint = Color(0xFF2ECC91), // Emerald green
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(R.string.lbl_folder_save_path),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }
                }
            }

            // Action Buttons or Permission Warning
            if (!hasPermissions) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.5.dp,
                            color = Color(0xFFFF4D5E).copy(alpha = 0.5f),
                            shape = RoundedCornerShape(20.dp)
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFF4D5E).copy(alpha = 0.12f)
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = stringResource(R.string.desc_storage_permission),
                            tint = Color(0xFFFF4D5E),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.lbl_storage_permission_required),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.lbl_storage_permission_desc),
                            fontSize = 12.sp,
                            color = Color(0xFF9EACBC),
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onGrantPermissions,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("grant_permission_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4D5E)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.btn_grant_permission), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                // Bottom Side-by-side luxurious actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Send Button - Neon Blue with glow
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0xFF3D8BFF))
                            .border(1.5.dp, Color(0xFF3D8BFF).copy(alpha = 0.6f), RoundedCornerShape(22.dp))
                            .bounceClick {
                                screenState = HomeScreenState.SEND_PREPARE
                            }
                            .testTag("send_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = stringResource(R.string.btn_send),
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.btn_send),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    // Receive Button - Emerald Green with glow
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0xFF2ECC91))
                            .border(1.5.dp, Color(0xFF2ECC91).copy(alpha = 0.6f), RoundedCornerShape(22.dp))
                            .bounceClick {
                                onReceiveClick()
                            }
                            .testTag("receive_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = stringResource(R.string.btn_receive),
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.btn_receive),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        } else {
            // SEND_PREPARE screen (شاشة الإرسال)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                // Elegant Glassmorphism card detailing send process
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0x0CFFFFFF))
                        .border(1.dp, Color(0x15FFFFFF), RoundedCornerShape(28.dp))
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(90.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF3D8BFF).copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Upload,
                                contentDescription = "Select File Icon",
                                tint = Color(0xFF3D8BFF),
                                modifier = Modifier.size(46.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = stringResource(R.string.ready_quick_share),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.click_below_select),
                            fontSize = 13.sp,
                            color = Color(0xFF9EACBC),
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Option 1: Choose Files (اختر ملفات)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .height(56.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF3D8BFF))
                        .border(1.5.dp, Color(0xFF3D8BFF).copy(alpha = 0.8f), RoundedCornerShape(18.dp))
                        .bounceClick {
                            onSendClick()
                        }
                        .testTag("send_files_btn"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.InsertDriveFile,
                            contentDescription = stringResource(R.string.icon_desc_files),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.btn_choose_files),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Option 2: Choose Folder (اختر مجلد)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .height(56.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF2ECC91))
                        .border(1.5.dp, Color(0xFF2ECC91).copy(alpha = 0.8f), RoundedCornerShape(18.dp))
                        .bounceClick {
                            onSendFolderClick()
                        }
                        .testTag("send_folder_btn"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = stringResource(R.string.icon_desc_folder),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.btn_choose_folder),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Option 3: Quick Text/Link Share (مشاركة نص / رابط)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .height(56.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF8B5CF6))
                        .border(1.5.dp, Color(0xFF8B5CF6).copy(alpha = 0.8f), RoundedCornerShape(18.dp))
                        .bounceClick {
                            onSendTextClick()
                        }
                        .testTag("send_text_btn"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = stringResource(R.string.icon_desc_text),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.btn_choose_text),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StaggeredCodeDisplay(code: String, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        code.forEachIndexed { index, char ->
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(key1 = code) {
                delay(index * 150L) // Staggered delay for each digit
                visible = true
            }

            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(400)) + scaleIn(initialScale = 0.6f, animationSpec = tween(400)),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 54.dp, height = 72.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x16FFFFFF)) // Glassmorphism semi-transparent white
                        .border(1.5.dp, Color(0xFF3D8BFF).copy(alpha = 0.8f), RoundedCornerShape(16.dp)), // Glowing neon blue border
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = char.toString(),
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF7CFFCB) // Lime success green for high contrast and luxury look
                    )
                }
            }
        }
    }
}

@Composable
fun SenderWaitingScreen(
    state: TransferState.SenderWaiting,
    onCancel: () -> Unit
) {
    var showLongWaitTip by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(30000) // 30 seconds
        showLongWaitTip = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Title block
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.card_waiting_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.card_waiting_desc),
                fontSize = 13.sp,
                color = Color(0xFF9EACBC),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // Huge Code Display + Radar
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier.size(240.dp),
                contentAlignment = Alignment.Center
            ) {
                RadarAnimation(modifier = Modifier.fillMaxSize())
                
                // Staggered code display with luxury glassmorphism look
                StaggeredCodeDisplay(code = state.code)
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.status_pending),
                fontSize = 14.sp,
                color = Color(0xFF3D8BFF),
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Beautiful direct connection instructions panel
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0x11FFFFFF)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0x22FFFFFF))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.btn_connect_manual),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF3D8BFF)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    // Keep the IP and Port values strictly LTR for clarity
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = "${stringResource(R.string.input_ip_label)}: ${state.localIp}\n${stringResource(R.string.manual_port_label)}: ${state.port}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )
                    }
                }
            }

            if (state.nsdRegistrationFailed) {
                // Shown immediately (no need to wait 30s) when automatic broadcasting
                // failed, so the user isn't left staring at a code that will never be
                // auto-discovered. The server is still listening, so manual IP still works.
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .testTag("nsd_registration_failed_banner"),
                    colors = CardDefaults.cardColors(containerColor = Color(0x22FF4D5E)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0x44FF4D5E))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.WifiOff,
                            contentDescription = null,
                            tint = Color(0xFFFF4D5E),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.sender_nsd_failed_tip),
                            color = Color.White,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            } else if (showLongWaitTip) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0x22FFB300)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0x44FFB300))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.sender_waiting_long_wait_tip),
                            color = Color.White,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        // Selected Files Summary and Cancel Button
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.toast_share_received, state.files.size),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.summary_total_size, formatSize(LocalContext.current, state.files.sumOf { it.size })),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("cancel_button"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(stringResource(R.string.btn_cancel), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SenderApprovalScreen(
    state: TransferState.SenderWaitingForApproval,
    onCancel: () -> Unit
) {
    var countdown by remember { mutableStateOf(30) }

    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
        state.onReject()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFFFB300),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.sender_approval_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.sender_approval_desc, state.receiverIp),
                fontSize = 14.sp,
                color = Color(0xFF9EACBC),
                textAlign = TextAlign.Center
            )
        }

        // List of Files
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0x11FFFFFF)),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0x22FFFFFF))
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.files) { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x05FFFFFF), RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = file.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = formatSize(LocalContext.current, file.size),
                            fontSize = 12.sp,
                            color = Color(0xFF9EACBC)
                        )
                    }
                }
            }
        }

        // Action controls
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.lbl_timeout_warning, countdown),
                color = Color(0xFFFF4D4D),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = state.onReject,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("reject_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x22FF4D4D)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0x44FF4D4D))
                ) {
                    Text(
                        text = stringResource(R.string.btn_reject),
                        color = Color(0xFFFF4D4D),
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = state.onAccept,
                    modifier = Modifier
                        .weight(1.2f)
                        .height(52.dp)
                        .testTag("accept_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3D8BFF)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.btn_accept),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun ReceiverConnectingScreen(
    state: TransferState.ReceiverConnecting,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(modifier = Modifier.size(10.dp)) // Spacer

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(64.dp),
                color = Color(0xFF3D8BFF), // Neon Send Blue
                strokeWidth = 5.dp
            )
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.connecting_verify_code, state.code),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = state.statusMessage,
                fontSize = 14.sp,
                color = Color(0xFF9EACBC),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }

        Button(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("cancel_button"),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0x1EFFFFFF)), // Glassmorphism Look
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(stringResource(R.string.btn_cancel), color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun RocketTransferAnimation(progress: Float, modifier: Modifier = Modifier) {
    val isFinished = progress >= 0.99f
    
    // Animate the celebratory takeoff if finished
    val takeoffOffset = remember { Animatable(0f) }
    val takeoffAlpha = remember { Animatable(1f) }
    val explosionScale = remember { Animatable(0f) }
    val explosionAlpha = remember { Animatable(0f) }
    
    LaunchedEffect(isFinished) {
        if (isFinished) {
            // 1. Shake/vibrate slightly
            repeat(3) {
                takeoffOffset.animateTo(5f, tween(50))
                takeoffOffset.animateTo(-5f, tween(50))
            }
            takeoffOffset.animateTo(0f, tween(50))
            
            // 2. Shoot up and disappear
            takeoffOffset.animateTo(-400f, tween(600, easing = EaseInBack))
            takeoffAlpha.animateTo(0f, tween(300))
            
            // 3. Trigger glow explosion
            explosionAlpha.snapTo(1f)
            explosionScale.animateTo(2.5f, tween(500, easing = EaseOutQuad))
            explosionAlpha.animateTo(0f, tween(500))
        } else {
            takeoffOffset.snapTo(0f)
            takeoffAlpha.snapTo(1f)
            explosionScale.snapTo(0f)
            explosionAlpha.snapTo(0f)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0x0CFFFFFF))
            .border(1.dp, Color(0x11FFFFFF), RoundedCornerShape(24.dp))
    ) {
        // Background stars or space dust for rich atmosphere
        Canvas(modifier = Modifier.fillMaxSize()) {
            val starColor = Color.White.copy(alpha = 0.35f)
            drawCircle(starColor, 2.dp.toPx(), Offset(size.width * 0.2f, size.height * 0.3f))
            drawCircle(starColor, 3.dp.toPx(), Offset(size.width * 0.75f, size.height * 0.25f))
            drawCircle(starColor, 1.5.dp.toPx(), Offset(size.width * 0.45f, size.height * 0.75f))
            drawCircle(starColor, 2.5.dp.toPx(), Offset(size.width * 0.1f, size.height * 0.7f))
            drawCircle(starColor, 2.dp.toPx(), Offset(size.width * 0.85f, size.height * 0.65f))
            
            // Draw the trajectory line
            drawLine(
                color = Color.White.copy(alpha = 0.12f),
                start = Offset(size.width * 0.15f, size.height * 0.8f),
                end = Offset(size.width * 0.85f, size.height * 0.2f),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
            )
        }

        // Compute rocket coordinate along the path
        val startX = 0.15f
        val endX = 0.85f
        val startY = 0.80f
        val endY = 0.20f
        
        val currentRatio = progress.coerceIn(0f, 1f)
        val rx = startX + (endX - startX) * currentRatio
        val ry = startY + (endY - startY) * currentRatio

        // Display the particle/glow trail
        if (currentRatio > 0.05f && !isFinished) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trailStart = Offset(size.width * startX, size.height * startY)
                val trailEnd = Offset(size.width * rx, size.height * ry)
                
                // Draw neon blue trail
                drawLine(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF3D8BFF).copy(alpha = 0.1f), Color(0xFF3D8BFF).copy(alpha = 0.8f)),
                        start = trailStart,
                        end = trailEnd
                    ),
                    start = trailStart,
                    end = trailEnd,
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round
                )
                
                // Inner bright core trail
                drawLine(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF7CFFCB).copy(alpha = 0f), Color(0xFF7CFFCB).copy(alpha = 0.9f)),
                        start = trailStart,
                        end = trailEnd
                    ),
                    start = trailStart,
                    end = trailEnd,
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        // Drawing the rocket container
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(
                    x = maxWidth * rx - 20.dp,
                    y = maxHeight * ry - 20.dp + (takeoffOffset.value).dp
                )
                .graphicsLayer {
                    alpha = takeoffAlpha.value
                    rotationZ = 45f
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFF3D8BFF), Color(0xFF1A1035))
                        )
                    )
                    .border(1.5.dp, Color(0xFF7CFFCB), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Rocket",
                    tint = Color.White,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = -45f }
                )
            }
        }

        // Takeoff explosion glow at the end
        if (isFinished && explosionAlpha.value > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(
                        x = maxWidth * endX - 40.dp,
                        y = maxHeight * endY - 40.dp
                    )
                    .size(80.dp)
                    .graphicsLayer {
                        scaleX = explosionScale.value
                        scaleY = explosionScale.value
                        alpha = explosionAlpha.value
                    }
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFF7CFFCB), Color(0x007CFFCB))
                        ),
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
fun ActiveTransferScreen(
    state: TransferState.ActiveTransfer,
    onCancel: () -> Unit
) {
    val overallPercent = if (state.totalSize > 0) {
        ((state.totalTransferred * 100) / state.totalSize).toFloat() / 100f
    } else {
        0f
    }

    val themeColor = if (state.isSender) Color(0xFF3D8BFF) else Color(0xFF2ECC91)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Role Header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (state.isSender) stringResource(R.string.notif_sending_title) else stringResource(R.string.notif_receiving_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Signature Rocket Animation
            RocketTransferAnimation(
                progress = overallPercent,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Overall Progress Metric (Glassmorphism Styled)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0x0CFFFFFF))
                    .border(1.dp, themeColor.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.percent_completed, (overallPercent * 100).toInt()),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = themeColor
                        )
                        Text(
                            text = formatSpeed(LocalContext.current, state.speedBytesPerSec),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            color = themeColor
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    LinearProgressIndicator(
                        progress = { overallPercent },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape),
                        color = themeColor,
                        trackColor = Color(0x1AFFFFFF)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.transferred_lbl, formatSize(LocalContext.current, state.totalTransferred)),
                            fontSize = 12.sp,
                            color = Color(0xFF9EACBC)
                        )
                        Text(
                            text = stringResource(R.string.total_size_lbl, formatSize(LocalContext.current, state.totalSize)),
                            fontSize = 12.sp,
                            color = Color(0xFF9EACBC)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val remainingBytes = (state.totalSize - state.totalTransferred).coerceAtLeast(0L)
                    val timeRemainingSec = if (state.speedBytesPerSec > 0) remainingBytes / state.speedBytesPerSec else -1L

                    val context = LocalContext.current
                    fun formatTimeRemaining(seconds: Long): String {
                        if (seconds < 0) return context.getString(R.string.calculating)
                        if (seconds == 0L) return context.getString(R.string.less_than_second)
                        val minutes = seconds / 60
                        val secs = seconds % 60
                        return if (minutes > 0) {
                            context.getString(R.string.minutes_and_seconds, minutes.toInt(), secs.toInt())
                        } else {
                            context.getString(R.string.seconds_only, secs.toInt())
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = themeColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(R.string.lbl_speed, formatSpeed(LocalContext.current, state.speedBytesPerSec)),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.HourglassEmpty,
                                contentDescription = null,
                                tint = themeColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(R.string.time_remaining_lbl, formatTimeRemaining(timeRemainingSec)),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        // Files List Scroll
        Text(
            text = stringResource(R.string.files_list_title),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            textAlign = TextAlign.Start
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.files, key = { it.id }) { file ->
                FileTransferRowItem(file = file)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Cancel Button - Branded custom Coral Red
        Button(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("cancel_button"),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4D5E)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(stringResource(R.string.btn_cancel_stop), color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun FileTransferRowItem(file: TransferFile) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0x0CFFFFFF)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0x15FFFFFF))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon + Name
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val statusIcon = when (file.status) {
                        TransferStatus.PENDING -> Icons.Default.HourglassEmpty
                        TransferStatus.TRANSFERRING -> Icons.Default.Refresh
                        TransferStatus.COMPLETED -> Icons.Default.CheckCircle
                        TransferStatus.FAILED -> Icons.Default.Error
                    }
                    val statusColor = when (file.status) {
                        TransferStatus.PENDING -> Color(0xFF9EACBC)
                        TransferStatus.TRANSFERRING -> Color(0xFF3D8BFF) // Neon Blue
                        TransferStatus.COMPLETED -> Color(0xFF7CFFCB) // Lime Success Green
                        TransferStatus.FAILED -> Color(0xFFFF4D5E) // Coral Red
                    }

                    Icon(
                        imageVector = statusIcon,
                        contentDescription = file.status.name,
                        tint = statusColor,
                        modifier = Modifier.size(24.dp)
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatSize(LocalContext.current, file.size),
                            fontSize = 11.sp,
                            color = Color(0xFF9EACBC)
                        )
                    }
                }

                // File percentage text
                Text(
                    text = "${(file.progress * 100).toInt()}%",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF3D8BFF)
                )
            }

            if (file.status == TransferStatus.TRANSFERRING) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { file.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape),
                    color = Color(0xFF3D8BFF),
                    trackColor = Color(0x1AFFFFFF)
                )
            }
        }
    }
}

@Composable
fun FinishedSummaryScreen(
    state: TransferState.Finished,
    onClose: () -> Unit,
    onManualIpClick: (() -> Unit)? = null
) {
    val statusColor = if (state.success) {
        Color(0xFF7CFFCB)
    } else if (state.isPartial) {
        Color(0xFFFFB300)
    } else {
        Color(0xFFFF4D5E)
    }

    val statusIcon = if (state.success) {
        Icons.Default.CheckCircle
    } else if (state.isPartial) {
        Icons.Default.Warning
    } else {
        Icons.Default.Cancel
    }

    val statusText = if (state.success) {
        stringResource(R.string.summary_success_title)
    } else if (state.isPartial) {
        stringResource(R.string.summary_partial_title)
    } else {
        stringResource(R.string.summary_failed_title)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(modifier = Modifier.size(10.dp)) // Spacer

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            // Status Icon with luxury glow
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.12f))
                    .border(1.5.dp, statusColor.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = statusText,
                    tint = statusColor,
                    modifier = Modifier.size(52.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = statusText,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (state.isPartial) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x16FFB300)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    border = BorderStroke(1.dp, Color(0x33FFB300))
                ) {
                    Text(
                        text = stringResource(R.string.summary_partial_count_lbl, state.successCount, state.totalFilesCount, state.totalFilesCount - state.successCount),
                        color = Color(0xFFFFB300),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (state.errorMsg != null && !state.isPartial) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x16FF4D5E)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    border = BorderStroke(1.dp, Color(0x33FF4D5E))
                ) {
                    Text(
                        text = state.errorMsg,
                        color = Color(0xFFFF4D5E),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Summary Info Block (Glassmorphism Styled)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0x0CFFFFFF)),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Color(0x12FFFFFF))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.session_details_header),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF3D8BFF),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    val context = LocalContext.current
                    SummaryRow(label = stringResource(R.string.summary_files_count_lbl), value = stringResource(R.string.summary_files_value, state.filesCount))
                    SummaryRow(label = stringResource(R.string.summary_total_size_lbl), value = formatSize(context, state.totalSize))
                    SummaryRow(label = stringResource(R.string.summary_time_elapsed_lbl), value = stringResource(R.string.summary_seconds_value, state.timeElapsedSec.toInt()))
                    SummaryRow(label = stringResource(R.string.summary_avg_speed_lbl), value = formatSpeed(context, state.averageSpeedBytesPerSec))
                }
            }

            val receivedText = ReceivedTextHolder.lastReceivedText
            if (receivedText != null && !state.isSender) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x163D8BFF)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Color(0x333D8BFF))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.received_text_lbl),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF3D8BFF)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                text = receivedText,
                                fontSize = 14.sp,
                                color = Color.White,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0x10FFFFFF), RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                             )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        val context = LocalContext.current
                        Button(
                            onClick = {
                                try {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("Swipe Share", receivedText)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, context.getString(R.string.toast_copied_success), Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {}
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3D8BFF)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.btn_copy_clipboard), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        val context = LocalContext.current
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!state.success || state.isPartial) {
                if (!state.isSender && onManualIpClick != null) {
                    Button(
                        onClick = onManualIpClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("manual_ip_fallback_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x1EFFFFFF)),
                        border = BorderStroke(1.dp, Color(0x44FFFFFF)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.btn_connect_manual_ip), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                Button(
                    onClick = {
                        TransferManager.retryTransfer(context, state.isSender)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("retry_transfer_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = Color.Black
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_retry), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            Button(
                onClick = onClose,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("dismiss_finished_button"),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3D8BFF)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(stringResource(R.string.btn_back_home), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 13.sp, color = Color(0xFF9EACBC))
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
fun RadarAnimation(modifier: Modifier = Modifier) {
    val primaryColor = Color(0xFF3D8BFF) // Glowing Neon Blue
    val transition = rememberInfiniteTransition(label = "radar")
    val radiusRatio by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radius"
    )
    val opacity by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "opacity"
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val maxRadius = size.minDimension / 2
        
        // Background static circle
        drawCircle(
            color = primaryColor.copy(alpha = 0.08f),
            radius = maxRadius,
            center = center
        )
        
        // Expanding pulse
        drawCircle(
            color = primaryColor.copy(alpha = opacity * 0.25f),
            radius = maxRadius * radiusRatio,
            center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
        )

        // Central core
        drawCircle(
            color = primaryColor,
            radius = 12.dp.toPx(),
            center = center
        )
    }
}

// --- 3-STAGE RESET APP SETTINGS FLOW ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsFlowDialog(
    onDismiss: () -> Unit,
    onResetConfirmed: () -> Unit,
    currentLanguage: String,
    onLanguageChanged: (String) -> Unit
) {
    var showResetWorkflow by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf(1) }
    var confirmInputText by remember { mutableStateOf("") }
    val context = LocalContext.current

    if (!showResetWorkflow) {
        // Main Settings Dialog
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = stringResource(R.string.settings_title),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Language Switcher Section
                    Column {
                        Text(
                            text = stringResource(R.string.settings_language),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onLanguageChanged("ar") },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (currentLanguage == "ar") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_lang_ar),
                                    color = if (currentLanguage == "ar") Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp
                                )
                            }
                            Button(
                                onClick = { onLanguageChanged("en") },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (currentLanguage == "en") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_lang_en),
                                    color = if (currentLanguage == "en") Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))

                    // Reactivate App Section
                    Column {
                        Button(
                            onClick = { showResetWorkflow = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.settings_reactivate),
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.btn_confirm), color = Color.White)
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    } else {
        // Reset App 3-Stage Workflow
        AlertDialog(
            onDismissRequest = { showResetWorkflow = false },
            title = {
                Text(
                    text = when (stage) {
                        1 -> "${stringResource(R.string.settings_reactivate)} (1/3)"
                        2 -> "${stringResource(R.string.settings_reactivate)} (2/3)"
                        else -> "${stringResource(R.string.settings_reactivate)} (3/3)"
                    },
                    fontWeight = FontWeight.Bold,
                    color = if (stage == 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    when (stage) {
                        1 -> {
                            Text(
                                text = stringResource(R.string.reset_confirm_msg_1),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                        }
                        2 -> {
                            Text(
                                text = stringResource(R.string.reset_confirm_msg_2),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                        }
                        3 -> {
                            Text(
                                text = stringResource(R.string.reset_confirm_msg_3, stringResource(R.string.reset_confirm_word)),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                            OutlinedTextField(
                                value = confirmInputText,
                                onValueChange = { confirmInputText = it },
                                placeholder = { Text(stringResource(R.string.reset_confirm_placeholder)) },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("reset_confirm_input"),
                                textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (stage < 3) {
                            stage++
                        } else {
                            if (confirmInputText.trim().lowercase() == context.getString(R.string.reset_confirm_word)) {
                                onResetConfirmed()
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reset_confirm_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (stage == 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.btn_confirm),
                        color = Color.White
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetWorkflow = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.btn_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

// --- LOCALIZATION HELPERS ---

fun getLocalizedContext(context: Context, lang: String): Context {
    val locale = java.util.Locale(lang)
    java.util.Locale.setDefault(locale)
    val config = android.content.res.Configuration(context.resources.configuration)
    config.setLocale(locale)
    config.setLayoutDirection(locale)
    return context.createConfigurationContext(config)
}


// --- WI-FI CONNECTION CHECKER ---

fun isConnectedToWifi(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) // hotspot or cellular can work as direct network
    } else {
        @Suppress("DEPRECATION")
        val activeNetworkInfo = cm.activeNetworkInfo
        activeNetworkInfo != null && (activeNetworkInfo.type == android.net.ConnectivityManager.TYPE_WIFI ||
                activeNetworkInfo.type == android.net.ConnectivityManager.TYPE_MOBILE)
    }
}

// --- PERMISSIONS HELPERS ---

fun hasStoragePermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        true
    } else {
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

fun requestStoragePermission(activity: MainActivity) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        activity.requestPermissions(
            arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ),
            1001
        )
    }
}

// --- SYSTEM RESET HANDLER ---

fun resetApp(context: Context) {
    try {
        // Clear SharedPreferences
        val sharedPrefs = context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
        sharedPrefs.edit().clear().apply()

        // Clear App Cache
        context.cacheDir.deleteRecursively()

        // Clean up active connections / server sockets
        TransferManager.cleanup()
        TransferManager.cancelTransfer()

        // Clear TransferHistory database table
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                db.transferHistoryDao().clearHistory()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error clearing history database", e)
            }
        }
    } catch (e: Exception) {
        Log.e("MainActivity", "Error during app reset", e)
    }
}

// --- HELPER METRIC FORMATTERS ---

fun formatSize(context: Context, size: Long): String {
    if (size <= 0) return "0 " + context.getString(R.string.unit_bytes)
    val units = arrayOf(
        context.getString(R.string.unit_bytes),
        context.getString(R.string.unit_kb),
        context.getString(R.string.unit_mb),
        context.getString(R.string.unit_gb),
        context.getString(R.string.unit_tb)
    )
    var digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    if (digitGroups >= units.size) {
        digitGroups = units.size - 1
    }
    return String.format(java.util.Locale.US, "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

fun formatSpeed(context: Context, bytesPerSec: Long): String {
    val kb = bytesPerSec / 1024.0
    return if (kb > 1024) {
        String.format(java.util.Locale.US, "%.1f %s", kb / 1024.0, context.getString(R.string.unit_mb_ps))
    } else {
        String.format(java.util.Locale.US, "%.1f %s", kb, context.getString(R.string.unit_kb_ps))
    }
}

// --- HISTORY SCREEN & DATE METRIC HELPERS ---

fun formatDateTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy/MM/dd hh:mm a", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

@Composable
fun HistoryScreen(
    historyList: List<TransferHistory>,
    onClearAll: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0x1AFFFFFF))
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = stringResource(R.string.btn_back),
                    tint = Color.White
                )
            }

            Text(
                text = stringResource(R.string.history_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            if (historyList.isNotEmpty()) {
                IconButton(
                    onClick = onClearAll,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0x16FF4D5E))
                        .testTag("clear_history_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.btn_clear_all),
                        tint = Color(0xFFFF4D5E)
                    )
                }
            } else {
                Box(modifier = Modifier.size(48.dp))
            }
        }

        if (historyList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = stringResource(R.string.history_empty_icon),
                        tint = Color(0xFF9EACBC),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.history_empty_desc),
                        color = Color(0xFF9EACBC),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(historyList) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0x0CFFFFFF)),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color(0x12FFFFFF))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Direction Indicator Glow
                            val iconColor = if (item.isSender) Color(0xFF3D8BFF) else Color(0xFF2ECC91)
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(iconColor.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (item.isSender) Icons.Default.Upload else Icons.Default.Download,
                                    contentDescription = null,
                                    tint = iconColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.filesSummary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = formatSize(LocalContext.current, item.totalSize),
                                        fontSize = 12.sp,
                                        color = Color(0xFF9EACBC)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(3.dp)
                                            .background(Color(0xFF9EACBC), CircleShape)
                                    )
                                    Text(
                                        text = formatDateTime(item.timestamp),
                                        fontSize = 11.sp,
                                        color = Color(0xFF708090)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Status Badge
                            val badgeColor = if (item.success) Color(0xFF7CFFCB) else Color(0xFFFF4D5E)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(badgeColor.copy(alpha = 0.12f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (item.success) stringResource(R.string.status_success) else stringResource(R.string.status_failed),
                                    color = badgeColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationPermissionWarningBanner(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val hasNotifPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasNotifPermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xEC2C1E21)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.5.dp, Color(0xFFFF4D5E)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .testTag("notif_permission_warning_banner")
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFF4D5E),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.notif_permission_warning_title),
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.notif_permission_warning_desc),
                            color = Color(0xFF9EACBC),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error opening settings", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4D5E)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.btn_enable_notif),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

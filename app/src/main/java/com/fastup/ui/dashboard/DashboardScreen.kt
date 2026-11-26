package com.fastup.ui.dashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import com.fastup.model.CameraConnectionState
import com.fastup.model.LiveViewerMode
import com.fastup.model.PhotoItem
import com.fastup.model.ServerConnectionState
import com.fastup.ui.gallery.PhotoGridItem
import com.fastup.ui.theme.*
import com.fastup.ui.viewmodel.MainViewModel

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onPhotoClick: (String) -> Unit = {},
    onNavigateToSettings: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val stats by viewModel.dashboardStats.collectAsState()
    val cameraState by viewModel.cameraState.collectAsState()
    val cameraModel by viewModel.cameraModel.collectAsState()
    val serverState by viewModel.serverConnectionState.collectAsState()
    val deviceStats by viewModel.deviceTransferStats.collectAsState()
    val storageFacts by viewModel.storageFacts.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val isUploadPaused by viewModel.isUploadPaused.collectAsState()
    val photos by viewModel.photos.collectAsState()
    val liveViewerMode by viewModel.liveViewerMode.collectAsState()
    val liveViewBitmap by viewModel.liveViewBitmap.collectAsState()
    val isLiveViewStreaming by viewModel.isLiveViewStreaming.collectAsState()
    var showShareDialog by remember { mutableStateOf(false) }

    // Real-time Phone Battery Tracking
    var phoneBatteryLevel by remember { mutableIntStateOf(100) }
    var isPhoneCharging by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        phoneBatteryLevel = (level * 100) / scale
                    }
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    isPhoneCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val initialStatus = context.registerReceiver(batteryReceiver, filter)
        initialStatus?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                phoneBatteryLevel = (level * 100) / scale
            }
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            isPhoneCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
        }
        onDispose {
            try {
                context.unregisterReceiver(batteryReceiver)
            } catch (_: Exception) {}
        }
    }

    // Network Strength & Type Tracking
    var networkInfoText by remember { mutableStateOf("Checking...") }
    var networkIcon by remember { mutableStateOf(Icons.Default.Wifi) }
    var networkColor by remember { mutableStateOf(Emerald500) }

    LaunchedEffect(Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        while (true) {
            try {
                if (cm != null) {
                    val activeNetwork = cm.activeNetwork
                    val capabilities = cm.getNetworkCapabilities(activeNetwork)
                    if (capabilities != null) {
                        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            var wifiDetails = "Wi-Fi"
                            try {
                                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                                val wifiInfo = wm?.connectionInfo
                                if (wifiInfo != null) {
                                    val rssi = wifiInfo.rssi
                                    val ssid = wifiInfo.ssid?.replace("\"", "")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
                                    wifiDetails = if (ssid != null && rssi != -127) "$ssid (${rssi}dBm)" else if (rssi != -127) "Wi-Fi (${rssi}dBm)" else "Wi-Fi"
                                }
                            } catch (_: Exception) {
                                wifiDetails = "Wi-Fi"
                            }
                            networkInfoText = wifiDetails
                            networkIcon = Icons.Default.Wifi
                            networkColor = Emerald500
                        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            networkInfoText = "Cellular (Active)"
                            networkIcon = Icons.Default.SignalCellularAlt
                            networkColor = Emerald500
                        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                            networkInfoText = "Ethernet"
                            networkIcon = Icons.Default.Lan
                            networkColor = Emerald500
                        } else {
                            networkInfoText = "Connected"
                            networkIcon = Icons.Default.Wifi
                            networkColor = Emerald500
                        }
                    } else {
                        networkInfoText = "Offline"
                        networkIcon = Icons.Default.WifiOff
                        networkColor = Rose500
                    }
                }
            } catch (e: Exception) {
                networkInfoText = "Online"
                networkIcon = Icons.Default.Wifi
                networkColor = Emerald500
            }
            delay(4000)
        }
    }

    // Manage Live View streaming lifecycle based on mode
    DisposableEffect(liveViewerMode, cameraState) {
        if (liveViewerMode == LiveViewerMode.LIVE_VIEWFINDER && cameraState == CameraConnectionState.CONNECTED) {
            viewModel.startLiveView()
        } else {
            viewModel.stopLiveView()
        }
        onDispose {
            if (liveViewerMode == LiveViewerMode.LIVE_VIEWFINDER) {
                viewModel.stopLiveView()
            }
        }
    }

    val latestPhoto = photos.firstOrNull()

    // Header label timeout when idle (stays visible for 4s, fades on idle, tap screen to reveal)
    var isHeaderVisible by remember { mutableStateOf(true) }
    var lastInteractionTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(lastInteractionTimestamp) {
        delay(4000)
        isHeaderVisible = false
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    @Composable
    fun CameraViewfinder(modifier: Modifier = Modifier) {
        val frameBitmap = remember(liveViewBitmap) { liveViewBitmap?.asImageBitmap() }
        val liveViewAspect = if (frameBitmap != null && frameBitmap.height > 0) {
            frameBitmap.width.toFloat() / frameBitmap.height.toFloat()
        } else {
            3f / 2f
        }

        Box(
            modifier = modifier
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    lastInteractionTimestamp = System.currentTimeMillis()
                    isHeaderVisible = !isHeaderVisible
                    if (liveViewerMode == LiveViewerMode.INSTANT_CAPTURE && latestPhoto != null) {
                        onPhotoClick(latestPhoto.id)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (liveViewerMode == LiveViewerMode.LIVE_VIEWFINDER) {
                if (frameBitmap != null) {
                    Image(
                        bitmap = frameBitmap,
                        contentDescription = "Live View Stream",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (cameraState == CameraConnectionState.CONNECTED) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Blue400,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Connecting to camera live viewfinder stream...",
                                color = Slate300,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.VideocamOff,
                                contentDescription = null,
                                tint = Slate500,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Connect camera via USB-C to view live stream",
                                color = Slate400,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // Live Stream Header Overlay (Times out when idle, tap viewfinder to toggle)
                androidx.compose.animation.AnimatedVisibility(
                    visible = isHeaderVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isLiveViewStreaming) "LIVE VIEWFINDER (SENSOR)" else "VIEWFINDER PAUSED",
                            color = Slate100,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )

                        if (cameraState == CameraConnectionState.CONNECTED) {
                            FilledTonalButton(
                                onClick = {
                                    lastInteractionTimestamp = System.currentTimeMillis()
                                    isHeaderVisible = true
                                    if (isLiveViewStreaming) viewModel.stopLiveView() else viewModel.startLiveView()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(24.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (isLiveViewStreaming) Slate700 else Blue600,
                                    contentColor = Slate100
                                )
                            ) {
                                Text(
                                    text = if (isLiveViewStreaming) "Pause" else "Resume",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            } else if (liveViewerMode == LiveViewerMode.INSTANT_CAPTURE) {
                if (latestPhoto != null) {
                    AsyncImage(
                        model = latestPhoto.displayFile,
                        contentDescription = "Latest Photo Preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Top Bar Overlay
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isHeaderVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.TopCenter)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "LATEST CAPTURE: ${latestPhoto.fileName}",
                                color = Slate100,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Text(
                                text = "Tap to inspect",
                                color = Slate300,
                                fontSize = 10.sp
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = Slate600,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Waiting for first capture...",
                            color = Slate400,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.VideocamOff,
                        contentDescription = null,
                        tint = Slate600,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Live Viewer Disabled",
                        color = Slate400,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }

    @Composable
    fun ColumnScope.DashboardControlsContent(isLandscapeLayout: Boolean) {
        val (cameraStatusText, cameraStatusColor) = when (cameraState) {
            CameraConnectionState.CONNECTED -> Pair("Ready", Emerald500)
            CameraConnectionState.CAPTURING -> Pair("Capturing", Amber500)
            CameraConnectionState.CONNECTING -> Pair("Connecting", Blue500)
            CameraConnectionState.DISCONNECTED -> Pair("Offline", Slate400)
            CameraConnectionState.ERROR -> Pair("Error", Rose500)
        }
        val cameraDetail = if (cameraState == CameraConnectionState.CONNECTED) {
            cameraModel.removePrefix("Sony ").ifBlank { "A7 III" }
        } else "--"

        val (serverStatusText, serverStatusColor) = when (serverState) {
            ServerConnectionState.CONNECTED -> Pair("Online", Emerald500)
            ServerConnectionState.CONNECTING -> Pair("Connecting", Blue500)
            ServerConnectionState.DISCONNECTED -> Pair("Offline", Slate400)
            ServerConnectionState.ERROR -> Pair("Session Expired", Amber500)
        }
        val serverHostDisplay = remember(viewModel.serverUrl) {
            try {
                val clean = viewModel.serverUrl.substringAfter("://").substringBefore(":").substringBefore("/")
                if (clean.length > 13) clean.take(11) + ".." else clean
            } catch (_: Exception) {
                "Server"
            }
        }

        val phoneBatteryColor = when {
            phoneBatteryLevel > 20 -> Emerald500
            phoneBatteryLevel > 10 -> Amber500
            else -> Rose500
        }

        // 1. Heading
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isLandscapeLayout) 4.dp else 12.dp)
                .padding(top = if (isLandscapeLayout) 4.dp else 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FastUp Dashboard",
                color = Slate100,
                fontSize = if (isLandscapeLayout) 17.sp else 20.sp,
                fontWeight = FontWeight.Bold
            )

            FilledTonalButton(
                onClick = { showShareDialog = true },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Blue600.copy(alpha = 0.25f),
                    contentColor = Blue400
                ),
                modifier = Modifier.height(28.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Share Event", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 1.5 Dedicated Alert & Issue Card (Appears dynamically when local or upload issues exist)
        val activeIssues by viewModel.activeIssues.collectAsState()
        StatusAlertCard(
            issues = activeIssues,
            onAction = { issue ->
                when (issue.actionType) {
                    com.fastup.model.IssueActionType.RECONNECT_CAMERA -> {
                        viewModel.reconnectCamera()
                        Toast.makeText(context, "Scanning for camera...", Toast.LENGTH_SHORT).show()
                    }
                    com.fastup.model.IssueActionType.RETRY_UPLOADS -> {
                        val count = viewModel.retryAll()
                        Toast.makeText(context, "Retrying $count upload(s)...", Toast.LENGTH_SHORT).show()
                    }
                    com.fastup.model.IssueActionType.TEST_CONNECTION -> {
                        viewModel.checkServerConnection()
                        Toast.makeText(context, "Testing server connection...", Toast.LENGTH_SHORT).show()
                    }
                    com.fastup.model.IssueActionType.RESUME_UPLOADS -> {
                        viewModel.startUpload()
                        Toast.makeText(context, "Resuming uploads...", Toast.LENGTH_SHORT).show()
                    }
                    com.fastup.model.IssueActionType.REPAIR_OTP,
                    com.fastup.model.IssueActionType.CLEAR_STORAGE -> {
                        onNavigateToSettings?.invoke() ?: run {
                            Toast.makeText(context, "Open Settings to resolve this issue", Toast.LENGTH_SHORT).show()
                        }
                    }
                    null -> {}
                }
            },
            modifier = Modifier.padding(horizontal = if (isLandscapeLayout) 4.dp else 12.dp)
        )

        // 2. Status Cards (Local Transfer & Upload Pipeline)
        if (isLandscapeLayout) {
            TransferPipelineCard(
                title = "Local Transfer",
                icon = Icons.Default.Download,
                statusText = cameraStatusText,
                statusColor = cameraStatusColor,
                onStatusClick = {
                    viewModel.reconnectCamera()
                    Toast.makeText(context, "Scanning for camera...", Toast.LENGTH_SHORT).show()
                },
                speedFormatted = deviceStats.speedFormatted,
                isActive = deviceStats.speedBps > 0L,
                progress = deviceStats.overallProgress,
                counter1 = Pair("Captured", deviceStats.totalCaptured.toString()),
                counter2 = Pair("Pending", deviceStats.pendingTransfer.toString()),
                counter3 = Pair("Done", deviceStats.completedTransfer.toString()),
                counter4 = Pair("Failed", deviceStats.failedTransfer.toString()),
                detailLabel = "Camera",
                detailValue = cameraDetail,
                fact1 = Pair("Used", storageFacts.localUsedFormatted),
                fact2 = Pair("Free", storageFacts.localFreeFormatted)
            )

            TransferPipelineCard(
                title = "Upload Pipeline",
                icon = Icons.Default.CloudUpload,
                badge = if (isUploadPaused) "PAUSED" else null,
                statusText = serverStatusText,
                statusColor = serverStatusColor,
                onStatusClick = {
                    viewModel.checkServerConnection()
                    Toast.makeText(context, "Checking server connection...", Toast.LENGTH_SHORT).show()
                },
                speedFormatted = stats.speedFormatted,
                isActive = isUploading,
                progress = stats.overallProgress,
                counter1 = Pair("Total", stats.totalPhotos.toString()),
                counter2 = Pair("Pending", stats.pendingCount.toString()),
                counter3 = Pair("Uploaded", stats.completedCount.toString()),
                counter4 = Pair("Failed", stats.failedCount.toString()),
                detailLabel = "Host",
                detailValue = serverHostDisplay,
                fact1 = Pair("Up", storageFacts.uploadedSizeFormatted),
                fact2 = Pair("Free", storageFacts.serverAvailableFormatted),
                onPendingClick = if (stats.pendingCount > 0) {
                    {
                        val count = viewModel.forceUploadPending()
                        Toast.makeText(context, "Force uploading $count pending photo(s)...", Toast.LENGTH_SHORT).show()
                    }
                } else null,
                onFailedClick = if (stats.failedCount > 0) { { viewModel.retryFailed() } } else null
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    TransferPipelineCard(
                        title = "Local Transfer",
                        icon = Icons.Default.Download,
                        statusText = cameraStatusText,
                        statusColor = cameraStatusColor,
                        onStatusClick = {
                            viewModel.reconnectCamera()
                            Toast.makeText(context, "Scanning for camera...", Toast.LENGTH_SHORT).show()
                        },
                        speedFormatted = deviceStats.speedFormatted,
                        isActive = deviceStats.speedBps > 0L,
                        progress = deviceStats.overallProgress,
                        counter1 = Pair("Captured", deviceStats.totalCaptured.toString()),
                        counter2 = Pair("Pending", deviceStats.pendingTransfer.toString()),
                        counter3 = Pair("Done", deviceStats.completedTransfer.toString()),
                        counter4 = Pair("Failed", deviceStats.failedTransfer.toString()),
                        detailLabel = "Camera",
                        detailValue = cameraDetail,
                        fact1 = Pair("Used", storageFacts.localUsedFormatted),
                        fact2 = Pair("Free", storageFacts.localFreeFormatted)
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    TransferPipelineCard(
                        title = "Upload Pipeline",
                        icon = Icons.Default.CloudUpload,
                        badge = if (isUploadPaused) "PAUSED" else null,
                        statusText = serverStatusText,
                        statusColor = serverStatusColor,
                        onStatusClick = {
                            viewModel.checkServerConnection()
                            Toast.makeText(context, "Checking server connection...", Toast.LENGTH_SHORT).show()
                        },
                        speedFormatted = stats.speedFormatted,
                        isActive = isUploading,
                        progress = stats.overallProgress,
                        counter1 = Pair("Total", stats.totalPhotos.toString()),
                        counter2 = Pair("Pending", stats.pendingCount.toString()),
                        counter3 = Pair("Uploaded", stats.completedCount.toString()),
                        counter4 = Pair("Failed", stats.failedCount.toString()),
                        detailLabel = "Host",
                        detailValue = serverHostDisplay,
                        fact1 = Pair("Up", storageFacts.uploadedSizeFormatted),
                        fact2 = Pair("Free", storageFacts.serverAvailableFormatted),
                        onPendingClick = if (stats.pendingCount > 0) {
                            {
                                val count = viewModel.forceUploadPending()
                                Toast.makeText(context, "Force uploading $count pending photo(s)...", Toast.LENGTH_SHORT).show()
                            }
                        } else null,
                        onFailedClick = if (stats.failedCount > 0) { { viewModel.retryFailed() } } else null
                    )
                }
            }
        }

        // 3. Telemetry Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isLandscapeLayout) 0.dp else 12.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, DarkBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1.1f)
                        .padding(end = 8.dp)
                ) {
                    Text(
                        text = "NETWORK",
                        color = Slate400,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = networkInfoText,
                        color = networkColor,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(26.dp)
                        .background(DarkBorder)
                )

                Column(
                    modifier = Modifier
                        .weight(0.9f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        text = "PHONE BATT",
                        color = Slate400,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "$phoneBatteryLevel%${if (isPhoneCharging) " ⚡" else ""}",
                        color = phoneBatteryColor,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }
            }
        }

        // 4. Recent Captures Section
        val capturesModifier = if (isLandscapeLayout) {
            Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
        } else {
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 6.dp)
                .weight(1f)
        }

        Column(
            modifier = capturesModifier,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Captures",
                    color = Slate100,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    text = "${photos.size} total",
                    color = Slate400,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (photos.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isLandscapeLayout) 100.dp else 160.dp)
                        .background(DarkSurface, RoundedCornerShape(12.dp))
                        .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = Slate600,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "No photos captured yet",
                            color = Slate400,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            } else if (isLandscapeLayout) {
                photos.take(8).chunked(2).forEach { rowPhotos ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowPhotos.forEach { photo ->
                            Box(modifier = Modifier.weight(1f)) {
                                PhotoGridItem(
                                    photo = photo,
                                    onClick = { onPhotoClick(photo.id) }
                                )
                            }
                        }
                        if (rowPhotos.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(photos, key = { it.id }) { photo ->
                        PhotoGridItem(
                            photo = photo,
                            onClick = { onPhotoClick(photo.id) }
                        )
                    }
                }
            }
        }
    }

    if (isLandscape) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(PureBlack)
        ) {
            // Left Side: Live Viewfinder / Preview (Native 3:2 camera aspect ratio, full height!)
            CameraViewfinder(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(3f / 2f, matchHeightConstraintsFirst = true)
            )

            // Right Side: Everything else (scrollable column)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DashboardControlsContent(isLandscapeLayout = true)
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PureBlack)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    lastInteractionTimestamp = System.currentTimeMillis()
                    isHeaderVisible = true
                },
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CameraViewfinder(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 2f)
            )

            DashboardControlsContent(isLandscapeLayout = false)
        }
    }

    if (showShareDialog) {
        com.fastup.ui.share.ShareModalDialog(
            title = "Folder: ${viewModel.sessionId}",
            targetPhoto = null,
            viewModel = viewModel,
            onDismiss = { showShareDialog = false }
        )
    }
}

/**
 * Shared, identical unified card design for Local Transfer & Upload Pipeline.
 * Fixed height (124.dp) ensures no resizing occurs when state changes.
 */
@Composable
fun TransferPipelineCard(
    title: String,
    icon: ImageVector,
    badge: String? = null,
    statusText: String,
    statusColor: Color,
    onStatusClick: (() -> Unit)? = null,
    speedFormatted: String,
    isActive: Boolean,
    progress: Float,
    counter1: Pair<String, String>, // Captured or Total
    counter2: Pair<String, String>, // Pending
    counter3: Pair<String, String>, // Transferred or Uploaded
    counter4: Pair<String, String>, // Failed
    detailLabel: String,            // Camera or Server
    detailValue: String,            // model or host
    fact1: Pair<String, String>,    // e.g. Storage Used
    fact2: Pair<String, String>,    // e.g. Storage Free
    onPendingClick: (() -> Unit)? = null,
    onFailedClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(124.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, DarkBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // Header Row: Title & Speed
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Blue400,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = title,
                        color = Slate100,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (badge != null) {
                        Surface(
                            color = Amber500.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(3.dp)
                        ) {
                            Text(
                                text = badge,
                                color = Amber500,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                // Speed Readout
                Text(
                    text = speedFormatted,
                    color = if (isActive) Emerald500 else Slate400,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }

            // Progress Bar
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .clip(RoundedCornerShape(1.dp)),
                color = Blue500,
                trackColor = Slate700
            )

            Spacer(modifier = Modifier.height(1.dp))

            // 1 Row, 2 Columns: Col 1 for ALL Counters in ONE column, Col 2 for Status, Details & Storage Facts
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Col 1: Counters (All 4 in ONE single vertical column)
                Column(
                    modifier = Modifier.weight(0.85f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    CounterRow(label = counter1.first, value = counter1.second, color = Slate300)
                    CounterRow(label = counter2.first, value = counter2.second, color = Amber500, onClick = onPendingClick)
                    CounterRow(label = counter3.first, value = counter3.second, color = Emerald500)
                    CounterRow(
                        label = counter4.first,
                        value = counter4.second,
                        color = Rose500,
                        onClick = onFailedClick
                    )
                }

                // Subtle Vertical Divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(54.dp)
                        .background(DarkBorder)
                )

                // Col 2: Status, Details & Storage Facts (4 rows matching Col 1)
                Column(
                    modifier = Modifier.weight(1.15f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    StatusDataRow(
                        label = "Status",
                        statusText = statusText,
                        dotColor = statusColor,
                        onClick = onStatusClick
                    )
                    FactDataRow(label = detailLabel, value = detailValue)
                    FactDataRow(label = fact1.first, value = fact1.second)
                    FactDataRow(label = fact2.first, value = fact2.second)
                }
            }
        }
    }
}

@Composable
fun StatusDataRow(
    label: String,
    statusText: String,
    dotColor: Color,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .clickable(onClick = onClick)
                } else Modifier
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Slate400,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(dotColor, CircleShape)
            )
            Text(
                text = statusText,
                color = dotColor,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                lineHeight = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun CounterRow(
    label: String,
    value: String,
    color: Color,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .clickable(onClick = onClick)
                } else Modifier
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Slate400,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 9.5.sp,
            lineHeight = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun FactDataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Slate400,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            color = Slate100,
            fontWeight = FontWeight.SemiBold,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}


@Composable
fun CameraBadge(cameraState: CameraConnectionState, model: String) {
    val (dotColor, label) = when (cameraState) {
        CameraConnectionState.CONNECTED -> Pair(Emerald500, model)
        CameraConnectionState.CAPTURING -> Pair(Amber500, "Transferring...")
        CameraConnectionState.CONNECTING -> Pair(Blue500, "Connecting...")
        CameraConnectionState.DISCONNECTED -> Pair(Slate400, "No Camera")
        CameraConnectionState.ERROR -> Pair(Rose500, "Error")
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .background(DarkSurface, RoundedCornerShape(10.dp))
            .border(1.dp, DarkBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(dotColor, CircleShape)
        )
        Text(text = label, color = Slate100, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

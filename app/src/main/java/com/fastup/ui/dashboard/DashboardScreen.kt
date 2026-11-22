package com.fastup.ui.dashboard

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
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
    onPhotoClick: (String) -> Unit = {}
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // App Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FastUp Dashboard",
                color = Slate100,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            if (liveViewerMode != LiveViewerMode.DISABLED) {
                Surface(
                    color = Blue500.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Blue500.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = if (liveViewerMode == LiveViewerMode.LIVE_VIEWFINDER) "LIVE VIEW" else "AUTO PREVIEW",
                        color = Blue400,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }

        // Side-by-Side: Local Transfer & Upload Pipeline Cards
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
            ServerConnectionState.ERROR -> Pair("Error", Rose500)
        }
        val serverHostDisplay = remember(viewModel.serverUrl) {
            try {
                val clean = viewModel.serverUrl.substringAfter("://").substringBefore(":").substringBefore("/")
                if (clean.length > 13) clean.take(11) + ".." else clean
            } catch (_: Exception) {
                "Server"
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
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
                    onFailedClick = if (stats.failedCount > 0) { { viewModel.retryFailed() } } else null
                )
            }
        }

        // Dedicated Live Viewer Section (if enabled)
        if (liveViewerMode == LiveViewerMode.INSTANT_CAPTURE && latestPhoto != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 2f),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, DarkBorder)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clickable { onPhotoClick(latestPhoto.id) },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = latestPhoto.displayFile,
                        contentDescription = "Latest Photo Preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Top Bar Overlay
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(Emerald500, CircleShape)
                            )
                            Text(
                                text = "LATEST CAPTURE: ${latestPhoto.fileName}",
                                color = Slate100,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = "Tap to inspect",
                            color = Slate300,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        } else if (liveViewerMode == LiveViewerMode.LIVE_VIEWFINDER) {
            val frameBitmap = remember(liveViewBitmap) {
                liveViewBitmap?.asImageBitmap()
            }
            val liveViewAspect = if (frameBitmap != null && frameBitmap.height > 0) {
                frameBitmap.width.toFloat() / frameBitmap.height.toFloat()
            } else {
                3f / 2f
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(liveViewAspect),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (isLiveViewStreaming) Emerald500.copy(alpha = 0.5f) else DarkBorder)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
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

                    // Live Stream Header Overlay
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(if (isLiveViewStreaming) Emerald500 else Rose500, CircleShape)
                            )
                            Text(
                                text = if (isLiveViewStreaming) "LIVE VIEWFINDER (SENSOR)" else "VIEWFINDER PAUSED",
                                color = Slate100,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (cameraState == CameraConnectionState.CONNECTED) {
                            FilledTonalButton(
                                onClick = {
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
            }
        }

        // Recent Images Grid Section filling remaining free space
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
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
                    fontSize = 15.sp
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
                        .fillMaxSize()
                        .background(DarkSurface, RoundedCornerShape(12.dp))
                        .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = Slate600,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "No photos captured yet",
                            color = Slate400,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Photos captured via USB tethering will appear here instantly.",
                            color = Slate500,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
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
                    CounterRow(label = counter2.first, value = counter2.second, color = Amber500)
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

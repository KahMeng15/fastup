package com.fastup.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fastup.model.CameraConnectionState
import com.fastup.ui.theme.*
import com.fastup.ui.viewmodel.MainViewModel
import com.fastup.util.QrCodeUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val stats by viewModel.dashboardStats.collectAsState()
    val cameraState by viewModel.cameraState.collectAsState()
    val cameraModel by viewModel.cameraModel.collectAsState()
    val deviceStats by viewModel.deviceTransferStats.collectAsState()
    val storageFacts by viewModel.storageFacts.collectAsState()
    val shareSession by viewModel.shareSession.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val isUploadPaused by viewModel.isUploadPaused.collectAsState()
    val targetFolder by viewModel.currentSessionId.collectAsState()

    var showFolderDialog by remember { mutableStateOf(false) }
    var sessionNameInput by remember(targetFolder) { mutableStateOf(targetFolder) }
    var isGeneratingShare by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
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
            // Camera Status Badge
            CameraBadge(cameraState = cameraState, model = cameraModel)
        }

        // Camera Tether Card (Fixed height to prevent resizing)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, DarkBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(
                            when (cameraState) {
                                CameraConnectionState.CONNECTED -> Emerald500.copy(alpha = 0.2f)
                                CameraConnectionState.CAPTURING -> Amber500.copy(alpha = 0.2f)
                                CameraConnectionState.CONNECTING -> Blue500.copy(alpha = 0.2f)
                                else -> Slate700
                            },
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Camera",
                        tint = when (cameraState) {
                            CameraConnectionState.CONNECTED -> Emerald500
                            CameraConnectionState.CAPTURING -> Amber500
                            CameraConnectionState.CONNECTING -> Blue500
                            else -> Slate400
                        },
                        modifier = Modifier.size(17.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (cameraState) {
                            CameraConnectionState.CONNECTED -> "Camera Connected & Ready"
                            CameraConnectionState.CAPTURING -> "Receiving Capture via USB..."
                            CameraConnectionState.CONNECTING -> "Handshaking with Camera..."
                            CameraConnectionState.DISCONNECTED -> "Connect Camera via USB-C"
                            CameraConnectionState.ERROR -> "USB Tether Error"
                        },
                        color = Slate100,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (cameraState == CameraConnectionState.CONNECTED)
                            "Physical shutter enabled (PC Remote mode)"
                        else "Ensure camera USB mode is set to 'PC Remote'",
                        color = Slate400,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    modifier = Modifier.width(88.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    if (cameraState != CameraConnectionState.CONNECTED) {
                        FilledTonalButton(
                            onClick = { viewModel.reconnectCamera() },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Slate700,
                                contentColor = Slate100
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Scan",
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Connect", fontSize = 10.5.sp)
                        }
                    } else {
                        Surface(
                            color = Emerald500.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .background(Emerald500, CircleShape)
                                )
                                Text("Ready", color = Emerald500, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Side-by-Side: Local Transfer & Upload Pipeline Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                TransferPipelineCard(
                    title = "Local Transfer",
                    icon = Icons.Default.Download,
                    speedFormatted = deviceStats.speedFormatted,
                    isActive = deviceStats.speedBps > 0L,
                    progress = deviceStats.overallProgress,
                    counter1 = Pair("Captured", deviceStats.totalCaptured.toString()),
                    counter2 = Pair("Pending", deviceStats.pendingTransfer.toString()),
                    counter3 = Pair("Done", deviceStats.completedTransfer.toString()),
                    counter4 = Pair("Failed", deviceStats.failedTransfer.toString()),
                    fact1 = Pair("Used", storageFacts.localUsedFormatted),
                    fact2 = Pair("Free", storageFacts.localFreeFormatted),
                    footerContent = {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = Slate500,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                text = deviceStats.currentFileName?.let { "Transferring..." } ?: "tether_captures",
                                color = Slate400,
                                fontSize = 8.5.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                TransferPipelineCard(
                    title = "Upload Pipeline",
                    icon = Icons.Default.CloudUpload,
                    badge = if (isUploadPaused) "PAUSED" else null,
                    speedFormatted = stats.speedFormatted,
                    isActive = isUploading,
                    progress = stats.overallProgress,
                    counter1 = Pair("Total", stats.totalPhotos.toString()),
                    counter2 = Pair("Pending", stats.pendingCount.toString()),
                    counter3 = Pair("Uploaded", stats.completedCount.toString()),
                    counter4 = Pair("Failed", stats.failedCount.toString()),
                    fact1 = Pair("Up", storageFacts.uploadedSizeFormatted),
                    fact2 = Pair("Free", storageFacts.serverAvailableFormatted),
                    footerContent = {
                        if (stats.failedCount > 0) {
                            Surface(
                                onClick = { viewModel.retryFailed() },
                                color = Rose500.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(0.5.dp, Rose500.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(17.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, tint = Rose500, modifier = Modifier.size(9.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text("Retry ${stats.failedCount}", color = Rose500, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else if (isUploadPaused) {
                            Surface(
                                onClick = { viewModel.startUpload() },
                                color = Emerald500.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(0.5.dp, Emerald500.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(17.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Emerald500, modifier = Modifier.size(9.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text("Resume", color = Emerald500, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (isUploading) Icons.Default.CloudSync else Icons.Default.CloudDone,
                                    contentDescription = null,
                                    tint = if (isUploading) Blue400 else Emerald500,
                                    modifier = Modifier.size(10.dp)
                                )
                                Text(
                                    text = if (isUploading) "Uploading..." else "Synced",
                                    color = Slate400,
                                    fontSize = 8.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                )
            }
        }

        // Target Server Folder Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, DarkBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Staging Destination",
                        color = Slate100,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    IconButton(onClick = { showFolderDialog = true }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Browse folders", tint = Blue500)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PureBlack, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = Amber500, modifier = Modifier.size(24.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Target Folder on Server:",
                            color = Slate400,
                            fontSize = 11.sp
                        )
                        Text(
                            text = targetFolder,
                            color = Slate100,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp
                        )
                    }
                }

                Button(
                    onClick = { showFolderDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Blue600),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select / Create Server Folder")
                }
            }
        }

        // Folder Selection Modal Dialog
        if (showFolderDialog) {
            ServerFolderDialog(
                viewModel = viewModel,
                onDismiss = { showFolderDialog = false }
            )
        }

        // Session & On-Demand Share Link Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, DarkBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Event Session & Guest Share",
                    color = Slate100,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )

                OutlinedTextField(
                    value = sessionNameInput,
                    onValueChange = {
                        sessionNameInput = it
                        viewModel.sessionId = it
                    },
                    label = { Text("Session Name / Event ID", color = Slate400) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Slate100,
                        unfocusedTextColor = Slate100,
                        focusedBorderColor = Blue500,
                        unfocusedBorderColor = Slate600
                    ),
                    singleLine = true
                )

                Button(
                    onClick = {
                        isGeneratingShare = true
                        viewModel.generateShareLink { result ->
                            isGeneratingShare = false
                            result.onFailure {
                                Toast.makeText(context, "Error: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !isGeneratingShare,
                    colors = ButtonDefaults.buttonColors(containerColor = Blue600),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isGeneratingShare) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Generate Instant Share Link")
                    }
                }

                // Share link display with QR code
                AnimatedVisibility(visible = shareSession != null) {
                    shareSession?.let { session ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(PureBlack, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Guest Live Gallery URL",
                                color = Slate400,
                                fontSize = 12.sp
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = session.shareUrl,
                                    color = Emerald500,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Share Link", session.shareUrl))
                                    Toast.makeText(context, "Copied share link to clipboard!", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Slate100)
                                }
                            }

                            // Dynamic QR code preview
                            val qrBitmap = remember(session.shareUrl) {
                                QrCodeUtil.generateQrCodeBitmap(session.shareUrl, 400)
                            }

                            qrBitmap?.let { bitmap ->
                                Box(
                                    modifier = Modifier
                                        .size(180.dp)
                                        .background(Color.White, RoundedCornerShape(8.dp))
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = "Share QR Code",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shared, identical unified card design for Local Transfer & Upload Pipeline.
 * Fixed height (140.dp) ensures no resizing occurs when state changes.
 */
@Composable
fun TransferPipelineCard(
    title: String,
    icon: ImageVector,
    badge: String? = null,
    speedFormatted: String,
    isActive: Boolean,
    progress: Float,
    counter1: Pair<String, String>, // Captured or Total
    counter2: Pair<String, String>, // Pending
    counter3: Pair<String, String>, // Transferred or Uploaded
    counter4: Pair<String, String>, // Failed
    fact1: Pair<String, String>,    // e.g. Storage Used
    fact2: Pair<String, String>,    // e.g. Storage Free
    footerContent: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(156.dp),
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

            // 1 Row, 2 Columns: Col 1 for ALL Counters in ONE column, Col 2 for Facts & Data
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Col 1: Counters (All 4 in ONE single vertical column)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    CounterRow(label = counter1.first, value = counter1.second, color = Slate300)
                    CounterRow(label = counter2.first, value = counter2.second, color = Amber500)
                    CounterRow(label = counter3.first, value = counter3.second, color = Emerald500)
                    CounterRow(label = counter4.first, value = counter4.second, color = Rose500)
                }

                // Subtle Vertical Divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(54.dp)
                        .background(DarkBorder)
                )

                // Col 2: Facts & Data (Used and Free in the other column)
                Column(
                    modifier = Modifier.weight(1.05f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FactDataRow(label = fact1.first, value = fact1.second)
                    FactDataRow(label = fact2.first, value = fact2.second)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Fixed Height Footer Slot (17.dp) ensures Card never resizes
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(17.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                footerContent()
            }
        }
    }
}

@Composable
fun CounterRow(label: String, value: String, color: Color) {
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

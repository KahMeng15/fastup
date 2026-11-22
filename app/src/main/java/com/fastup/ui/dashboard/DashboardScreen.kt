package com.fastup.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
            .background(Slate900)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
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
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            // Camera Status Badge
            CameraBadge(cameraState = cameraState, model = cameraModel)
        }

        // Camera Tether Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
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
                        }
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (cameraState) {
                            CameraConnectionState.CONNECTED -> "Camera Connected & Ready"
                            CameraConnectionState.CAPTURING -> "Receiving Capture via USB..."
                            CameraConnectionState.CONNECTING -> "Handshaking with Camera..."
                            CameraConnectionState.DISCONNECTED -> "Connect Sony A7 III via USB-C"
                            CameraConnectionState.ERROR -> "USB Tether Error"
                        },
                        color = Slate100,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = if (cameraState == CameraConnectionState.CONNECTED)
                            "Physical shutter enabled (PC Remote mode)"
                        else "Ensure camera USB mode is set to 'PC Remote'",
                        color = Slate400,
                        fontSize = 12.sp
                    )
                }

                if (cameraState != CameraConnectionState.CONNECTED) {
                    FilledTonalButton(
                        onClick = { viewModel.reconnectCamera() },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Slate700,
                            contentColor = Slate100
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Scan",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Connect", fontSize = 12.sp)
                    }
                }
            }
        }

        // Camera to Device Transfer Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(16.dp)
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = Blue400,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Camera → Device Transfer",
                            color = Slate100,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                    }

                    if (deviceStats.pendingTransfer > 0) {
                        Surface(
                            color = Amber500.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Transferring...",
                                color = Amber500,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    } else if (deviceStats.totalCaptured > 0) {
                        Surface(
                            color = Emerald500.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Synced to Device",
                                color = Emerald500,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatChip(label = "Captured", value = deviceStats.totalCaptured.toString(), color = Slate400)
                    StatChip(label = "Pending", value = deviceStats.pendingTransfer.toString(), color = Amber500)
                    StatChip(label = "Transferred", value = deviceStats.completedTransfer.toString(), color = Emerald500)
                    StatChip(label = "Failed", value = deviceStats.failedTransfer.toString(), color = Rose500)
                }

                deviceStats.currentFileName?.let { currentName ->
                    Text(
                        text = "Transferring: $currentName",
                        color = Slate400,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Real-Time Upload Progress & Speed Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(16.dp)
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Upload Pipeline",
                            color = Slate100,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        if (isUploadPaused) {
                            Surface(
                                color = Amber500.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "PAUSED",
                                    color = Amber500,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    // Transfer Speed Readout
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "Speed",
                            tint = if (isUploading) Emerald500 else Slate400,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stats.speedFormatted,
                            color = if (isUploading) Emerald500 else Slate400,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }

                // Progress Bar
                LinearProgressIndicator(
                    progress = { stats.overallProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = Blue500,
                    trackColor = Slate700
                )

                // Stats Chips Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatChip(label = "Total", value = stats.totalPhotos.toString(), color = Slate400)
                    StatChip(label = "Pending", value = stats.pendingCount.toString(), color = Amber500)
                    StatChip(label = "Uploaded", value = stats.completedCount.toString(), color = Emerald500)
                    StatChip(label = "Failed", value = stats.failedCount.toString(), color = Rose500)
                }

                // Retry Failed Button
                if (stats.failedCount > 0) {
                    Button(
                        onClick = { viewModel.retryFailed() },
                        colors = ButtonDefaults.buttonColors(containerColor = Rose500),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retry ${stats.failedCount} Failed Uploads")
                    }
                }

                // Paused Resume Button
                if (isUploadPaused) {
                    Button(
                        onClick = { viewModel.startUpload() },
                        colors = ButtonDefaults.buttonColors(containerColor = Emerald500),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Resume Upload Pipeline", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Target Server Folder Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(16.dp)
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
                        .background(Slate900, RoundedCornerShape(10.dp))
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
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(16.dp)
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
                                .background(Slate900, RoundedCornerShape(12.dp))
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
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .background(Slate800, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape)
        )
        Text(text = label, color = Slate100, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun StatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = color, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(text = label, color = Slate400, fontSize = 12.sp)
    }
}

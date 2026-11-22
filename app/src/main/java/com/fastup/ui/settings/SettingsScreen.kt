package com.fastup.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fastup.model.UploadFormat
import com.fastup.ui.theme.*
import com.fastup.ui.viewmodel.MainViewModel

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val isUploadPaused by viewModel.isUploadPaused.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val localFolders by viewModel.localFolders.collectAsState()
    val photographerName by viewModel.photographerName.collectAsState()
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var folderDropdownExpanded by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showPairDialog by remember { mutableStateOf(false) }
    var pairOtpCode by remember { mutableStateOf("") }
    var pairServerUrl by remember { mutableStateOf(viewModel.serverUrl) }
    var isPairing by remember { mutableStateOf(false) }

    var urlInput by remember { mutableStateOf(viewModel.serverUrl) }
    var apiKeyInput by remember { mutableStateOf(viewModel.apiKey) }
    var selectedFormat by remember { mutableStateOf(viewModel.uploadFormat) }
    var autoUpload by remember { mutableStateOf(viewModel.autoUpload) }
    var wifiOnly by remember { mutableStateOf(viewModel.wifiOnly) }

    LaunchedEffect(Unit) {
        viewModel.refreshLocalFolders()
    }

    LaunchedEffect(localFolders) {
        if (selectedFolderId == null || localFolders.none { it.id == selectedFolderId }) {
            selectedFolderId = localFolders.firstOrNull()?.id
        }
    }

    val selectedFolder = localFolders.find { it.id == selectedFolderId } ?: localFolders.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate900)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings",
            color = Slate100,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        // Photographer Pairing Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (photographerName != null) Emerald500.copy(alpha = 0.12f) else Slate800
            ),
            shape = RoundedCornerShape(16.dp),
            border = if (photographerName != null) androidx.compose.foundation.BorderStroke(1.dp, Emerald500.copy(alpha = 0.4f)) else null
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
                            imageVector = Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = if (photographerName != null) Emerald500 else Blue500,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (photographerName != null) "HelloMyPhotos Connected" else "Pair with HelloMyPhotos",
                            color = Slate100,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                    }

                    if (photographerName != null) {
                        Surface(
                            color = Emerald500.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "PAIRED",
                                color = Emerald500,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                if (photographerName != null) {
                    Text(
                        text = "Photographer: $photographerName\nTarget Session: ${viewModel.sessionId}",
                        color = Slate300,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )

                    OutlinedButton(
                        onClick = {
                            viewModel.disconnectPhotographer()
                            Toast.makeText(context, "Photographer session disconnected", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Rose500),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Disconnect / Switch Photographer")
                    }
                } else {
                    Text(
                        text = "Enter a 6-digit OTP code generated from HelloMyPhotos admin to connect without needing master API keys.",
                        color = Slate400,
                        fontSize = 13.sp
                    )

                    Button(
                        onClick = {
                            pairServerUrl = viewModel.serverUrl
                            pairOtpCode = ""
                            showPairDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Blue500),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enter 6-Digit Code")
                    }
                }
            }
        }

        // Upload Queue Control Card (Force Stop / Start)
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
                        text = "Upload Queue Control",
                        color = Slate100,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Surface(
                        color = if (isUploadPaused) Amber500.copy(alpha = 0.2f) else Emerald500.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (isUploadPaused) "PAUSED / STOPPED" else if (isUploading) "UPLOADING" else "ACTIVE",
                            color = if (isUploadPaused) Amber500 else Emerald500,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Text(
                    text = if (isUploadPaused)
                        "Upload queue is currently stopped. In-flight transfers are halted and new captures will be held until you resume."
                    else
                        "Upload queue is actively running and processing captured photos in the background.",
                    color = Slate400,
                    fontSize = 12.sp
                )

                if (isUploadPaused) {
                    Button(
                        onClick = {
                            viewModel.startUpload()
                            Toast.makeText(context, "Upload queue started!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Emerald500),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start / Resume Upload", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = {
                            viewModel.forceStopUpload()
                            Toast.makeText(context, "Upload queue force stopped", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Rose500),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Force Stop Upload", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Upload Format Card
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
                    text = "Upload Format",
                    color = Slate100,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = "Choose which file format gets uploaded to the staging server",
                    color = Slate400,
                    fontSize = 12.sp
                )

                UploadFormat.values().forEach { format ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedFormat == format,
                            onClick = {
                                selectedFormat = format
                                viewModel.uploadFormat = format
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = Blue500)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = format.label, color = Slate100, fontSize = 15.sp)
                    }
                }
            }
        }

        // Server URL Configuration Card
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
                    text = "Gallery Server (Frontend Port 8000)",
                    color = Slate100,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = "Point to your computer's IP on port 8000. The frontend proxies all API calls to the backend automatically.",
                    color = Slate400,
                    fontSize = 12.sp
                )

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = {
                        urlInput = it
                        viewModel.serverUrl = it
                    },
                    label = { Text("Server URL (e.g. http://10.0.0.43:8000)", color = Slate400) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Slate100,
                        unfocusedTextColor = Slate100,
                        focusedBorderColor = Blue500,
                        unfocusedBorderColor = Slate600
                    ),
                    singleLine = true
                )

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = {
                        apiKeyInput = it
                        viewModel.apiKey = it
                    },
                    label = { Text("Staging API Key (STAGING_API_KEY)", color = Slate400) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Slate100,
                        unfocusedTextColor = Slate100,
                        focusedBorderColor = Blue500,
                        unfocusedBorderColor = Slate600
                    ),
                    singleLine = true
                )
            }
        }

        // Network & Automation Card
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
                    text = "Automation & Network",
                    color = Slate100,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Upload Captures", color = Slate100, fontSize = 15.sp)
                        Text("Queue photos automatically upon capture", color = Slate400, fontSize = 12.sp)
                    }
                    Switch(
                        checked = autoUpload,
                        onCheckedChange = {
                            autoUpload = it
                            viewModel.autoUpload = it
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Blue500)
                    )
                }

                HorizontalDivider(color = Slate700, thickness = 1.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Wi-Fi Only Mode", color = Slate100, fontSize = 15.sp)
                        Text("Pause uploads when using Cellular data", color = Slate400, fontSize = 12.sp)
                    }
                    Switch(
                        checked = wifiOnly,
                        onCheckedChange = {
                            wifiOnly = it
                            viewModel.wifiOnly = it
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Blue500)
                    )
                }
            }
        }

        // Local Storage Management Card (Clear Local Storage)
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
                        text = "Local Storage Management",
                        color = Slate100,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    IconButton(
                        onClick = { viewModel.refreshLocalFolders() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = Slate400,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Text(
                    text = "Select a local folder on your phone to inspect space used and delete captures to free up storage.",
                    color = Slate400,
                    fontSize = 12.sp
                )

                if (localFolders.isEmpty()) {
                    Text(
                        text = "No local captures found in phone storage.",
                        color = Slate400,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    // Folder Selector Dropdown Box
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedCard(
                            onClick = { folderDropdownExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.outlinedCardColors(containerColor = Slate900),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = Amber500,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Column {
                                        Text(
                                            text = selectedFolder?.displayName ?: "Select folder",
                                            color = Slate100,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        )
                                        if (selectedFolder != null) {
                                            Text(
                                                text = "${selectedFolder.fileCount} photos • ${formatStorageBytes(selectedFolder.totalSizeBytes)}",
                                                color = Slate400,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                }
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Dropdown",
                                    tint = Slate400
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = folderDropdownExpanded,
                            onDismissRequest = { folderDropdownExpanded = false },
                            modifier = Modifier.background(Slate800)
                        ) {
                            localFolders.forEach { folder ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(folder.displayName, color = Slate100, fontWeight = FontWeight.Medium)
                                            Text(
                                                "${folder.fileCount} photos • ${formatStorageBytes(folder.totalSizeBytes)}",
                                                color = Slate400,
                                                fontSize = 11.sp
                                            )
                                        }
                                    },
                                    onClick = {
                                        selectedFolderId = folder.id
                                        folderDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Button to Clear Selected Folder
                    Button(
                        onClick = { showClearDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Rose500),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Clear Local Storage (${selectedFolder?.fileCount ?: 0} photos)",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Staging Cleanup Card
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
                    text = "Event Cleanup",
                    color = Slate100,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = "Trigger cleanup of server staging volume after editing is done and archived to media_ro",
                    color = Slate400,
                    fontSize = 12.sp
                )

                Button(
                    onClick = {
                        viewModel.cleanupCurrentSession { success ->
                            if (success) {
                                Toast.makeText(context, "Session cleaned up successfully on server!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Failed to clean up session on server", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Rose500),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clean Up Server Staging Volume")
                }
            }
        }

        // Confirmation Dialog for Clearing Local Storage
        if (showClearDialog && selectedFolder != null) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = {
                    Text(text = "Clear Local Photos?", color = Slate100, fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        text = "Are you sure you want to permanently delete ${selectedFolder.fileCount} photo(s) (${formatStorageBytes(selectedFolder.totalSizeBytes)}) from '${selectedFolder.displayName}'?\n\nThis will free up phone storage and remove them from the upload queue.",
                        color = Slate300,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val folderToDelete = selectedFolder.id
                            val folderName = selectedFolder.displayName
                            showClearDialog = false
                            viewModel.clearLocalStorage(folderToDelete) { success ->
                                if (success) {
                                    Toast.makeText(context, "Cleared $folderName from storage!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Failed to clear local storage", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Rose500)
                    ) {
                        Text("Delete Permanently")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text("Cancel", color = Slate400)
                    }
                },
                containerColor = Slate800,
                shape = RoundedCornerShape(16.dp)
            )
        }

        // Pair via 6-Digit OTP Dialog
        if (showPairDialog) {
            AlertDialog(
                onDismissRequest = { if (!isPairing) showPairDialog = false },
                title = {
                    Text("Pair via 6-Digit Code", color = Slate100, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Enter the 6-digit OTP code generated in HelloMyPhotos Settings:",
                            color = Slate300,
                            fontSize = 13.sp
                        )

                        OutlinedTextField(
                            value = pairServerUrl,
                            onValueChange = { pairServerUrl = it },
                            label = { Text("Server URL") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Blue500,
                                unfocusedBorderColor = Slate600,
                                focusedTextColor = Slate100,
                                unfocusedTextColor = Slate100
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = pairOtpCode,
                            onValueChange = { if (it.length <= 6) pairOtpCode = it.filter { ch -> ch.isDigit() } },
                            label = { Text("6-Digit OTP Code") },
                            placeholder = { Text("123456") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Blue500,
                                unfocusedBorderColor = Slate600,
                                focusedTextColor = Slate100,
                                unfocusedTextColor = Slate100
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (pairOtpCode.length != 6) {
                                Toast.makeText(context, "Please enter a 6-digit code", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isPairing = true
                            viewModel.pairWithOtp(pairServerUrl.trim(), pairOtpCode.trim()) { res ->
                                isPairing = false
                                res.onSuccess { pName ->
                                    showPairDialog = false
                                    urlInput = viewModel.serverUrl
                                    apiKeyInput = viewModel.apiKey
                                    selectedFormat = viewModel.uploadFormat
                                    autoUpload = viewModel.autoUpload
                                    wifiOnly = viewModel.wifiOnly
                                    Toast.makeText(context, "Connected as $pName!", Toast.LENGTH_LONG).show()
                                }.onFailure { err ->
                                    Toast.makeText(context, "Pairing failed: ${err.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Blue500),
                        enabled = !isPairing && pairOtpCode.length == 6
                    ) {
                        if (isPairing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Slate100, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connecting...")
                        } else {
                            Text("Connect")
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showPairDialog = false },
                        enabled = !isPairing
                    ) {
                        Text("Cancel", color = Slate400)
                    }
                },
                containerColor = Slate800,
                shape = RoundedCornerShape(16.dp)
            )
        }

        // Confirmation Dialog for Clearing Local Storage
        if (showClearDialog && selectedFolder != null) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = {
                    Text(text = "Clear Local Photos?", color = Slate100, fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        text = "Are you sure you want to permanently delete ${selectedFolder.fileCount} photo(s) (${formatStorageBytes(selectedFolder.totalSizeBytes)}) from '${selectedFolder.displayName}'?\n\nThis will free up phone storage and remove them from the upload queue.",
                        color = Slate300,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val folderToDelete = selectedFolder.id
                            val folderName = selectedFolder.displayName
                            showClearDialog = false
                            viewModel.clearLocalStorage(folderToDelete) { success ->
                                if (success) {
                                    Toast.makeText(context, "Cleared $folderName from storage!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Failed to clear local storage", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Rose500)
                    ) {
                        Text("Delete Permanently")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text("Cancel", color = Slate400)
                    }
                },
                containerColor = Slate800,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

private fun formatStorageBytes(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes.toDouble() / (1024 * 1024 * 1024))
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes.toDouble() / (1024 * 1024))
        bytes >= 1024L -> String.format("%.0f KB", bytes.toDouble() / 1024)
        else -> "$bytes B"
    }
}

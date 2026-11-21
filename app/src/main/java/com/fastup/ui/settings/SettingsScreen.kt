package com.fastup.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
    var urlInput by remember { mutableStateOf(viewModel.serverUrl) }
    var apiKeyInput by remember { mutableStateOf(viewModel.apiKey) }
    var selectedFormat by remember { mutableStateOf(viewModel.uploadFormat) }
    var autoUpload by remember { mutableStateOf(viewModel.autoUpload) }
    var wifiOnly by remember { mutableStateOf(viewModel.wifiOnly) }

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
    }
}

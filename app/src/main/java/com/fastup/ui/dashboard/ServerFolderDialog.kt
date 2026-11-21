package com.fastup.ui.dashboard

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.fastup.ui.theme.*
import com.fastup.ui.viewmodel.MainViewModel

@Composable
fun ServerFolderDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val currentFolder by viewModel.currentSessionId.collectAsState()
    val serverFolders by viewModel.serverFolders.collectAsState()
    val isLoading by viewModel.isLoadingFolders.collectAsState()

    // Navigation state: current directory path in the browser
    var currentNavPath by remember(currentFolder) { mutableStateOf(currentFolder) }
    var selectedTarget by remember(currentFolder) { mutableStateOf(currentFolder) }
    var newFolderName by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadServerFolders()
    }

    // Helper to find immediate child subfolders at currentNavPath
    val childFolders = remember(serverFolders, currentNavPath) {
        val prefix = if (currentNavPath.isEmpty()) "" else "$currentNavPath/"
        val set = mutableSetOf<String>()
        for (f in serverFolders) {
            val clean = f.trim('/')
            if (currentNavPath.isEmpty()) {
                val seg = clean.split('/').firstOrNull()?.trim()
                if (!seg.isNullOrEmpty()) set.add(seg)
            } else if (clean.startsWith(prefix) && clean != currentNavPath) {
                val sub = clean.removePrefix(prefix)
                val seg = sub.split('/').firstOrNull()?.trim()
                if (!seg.isNullOrEmpty()) set.add(seg)
            }
        }
        set.sorted()
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Slate800)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Browse Server Folders",
                            color = Slate100,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "Navigate folders & select upload destination",
                            color = Slate400,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(
                        onClick = { viewModel.loadServerFolders() },
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Blue500,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Slate400)
                        }
                    }
                }

                HorizontalDivider(color = Slate700)

                // Breadcrumb & Up Navigation Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Slate900)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (currentNavPath.contains('/')) {
                                currentNavPath = currentNavPath.substringBeforeLast('/')
                            } else {
                                currentNavPath = ""
                            }
                        },
                        enabled = currentNavPath.isNotEmpty(),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Up",
                            tint = if (currentNavPath.isNotEmpty()) Slate200 else Slate600,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = if (currentNavPath.isEmpty()) "Root /" else "Root / $currentNavPath",
                        color = if (currentNavPath.isEmpty()) Slate400 else Emerald400,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 2
                    )

                    if (currentNavPath.isNotEmpty()) {
                        IconButton(
                            onClick = { currentNavPath = "" },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = "Root",
                                tint = Slate400,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Current Directory Selection Card
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selectedTarget == currentNavPath) Blue600.copy(alpha = 0.2f) else Slate900)
                        .border(
                            width = if (selectedTarget == currentNavPath) 1.5.dp else 1.dp,
                            color = if (selectedTarget == currentNavPath) Blue500 else Slate700,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            selectedTarget = currentNavPath
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = if (selectedTarget == currentNavPath) Blue400 else Slate300,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            val currentDisplayName: String = if (currentNavPath.isEmpty()) "(Root)" else currentNavPath.substringAfterLast('/')
                            Text(
                                text = "Current: $currentDisplayName",
                                color = Slate100,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = if (selectedTarget == currentNavPath) "✓ Selected as upload destination" else "Tap to choose this folder",
                                color = if (selectedTarget == currentNavPath) Emerald400 else Slate500,
                                fontSize = 11.sp
                            )
                        }
                    }

                    RadioButton(
                        selected = selectedTarget == currentNavPath,
                        onClick = { selectedTarget = currentNavPath },
                        colors = RadioButtonDefaults.colors(selectedColor = Blue500)
                    )
                }

                // Subfolders List
                Text(
                    text = "Subfolders inside ${if (currentNavPath.isEmpty()) "Root" else currentNavPath.substringAfterLast('/')}",
                    color = Slate300,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )

                if (childFolders.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Slate900)
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isLoading) "Loading server folders..." else "No subfolders here. You can create one below or select this folder.",
                            color = Slate500,
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(childFolders) { folderName ->
                            val fullSubPath = if (currentNavPath.isEmpty()) folderName else "$currentNavPath/$folderName"
                            val isSelected = selectedTarget == fullSubPath

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Blue600.copy(alpha = 0.25f) else Slate900)
                                    .border(
                                        width = if (isSelected) 1.5.dp else 0.dp,
                                        color = if (isSelected) Blue500 else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        // Navigate into this folder
                                        currentNavPath = fullSubPath
                                        selectedTarget = fullSubPath
                                    }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = if (isSelected) Blue400 else Slate400,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = folderName,
                                        color = if (isSelected) Slate100 else Slate300,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 14.sp
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Open",
                                        color = Slate400,
                                        fontSize = 11.sp
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = "Open",
                                        tint = Slate400,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = Slate700)

                // Create Subfolder in current directory
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        placeholder = {
                            Text(
                                text = "New subfolder name",
                                color = Slate500,
                                fontSize = 12.sp
                            )
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Slate100,
                            unfocusedTextColor = Slate100,
                            focusedBorderColor = Blue500,
                            unfocusedBorderColor = Slate600
                        )
                    )

                    Button(
                        onClick = {
                            val trimmed = newFolderName.trim()
                            if (trimmed.isBlank()) {
                                Toast.makeText(context, "Please enter a folder name", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val targetPathToCreate = if (currentNavPath.isEmpty()) trimmed else "$currentNavPath/$trimmed"
                            isCreating = true
                            viewModel.createServerFolder(targetPathToCreate) { res ->
                                isCreating = false
                                res.fold(
                                    onSuccess = { created ->
                                        currentNavPath = created
                                        selectedTarget = created
                                        newFolderName = ""
                                        Toast.makeText(context, "Created '$created'", Toast.LENGTH_SHORT).show()
                                    },
                                    onFailure = {
                                        Toast.makeText(context, "Error: ${it.message}", Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        },
                        enabled = !isCreating && newFolderName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Emerald500),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        if (isCreating) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                        } else {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Create", fontSize = 12.sp)
                        }
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Slate400)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            val finalFolder = if (selectedTarget.isBlank()) currentNavPath else selectedTarget
                            viewModel.selectServerFolder(finalFolder)
                            Toast.makeText(context, "Target set to '${if (finalFolder.isEmpty()) "Root" else finalFolder}'", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1.6f),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue600)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (selectedTarget.isEmpty()) "Select Root" else "Select This Folder",
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

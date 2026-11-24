package com.fastup.ui.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fastup.model.*
import com.fastup.ui.theme.*
import com.fastup.ui.viewmodel.MainViewModel
import com.fastup.util.QrCodeUtil

/**
 * Universal Dual-Share Dialog:
 * Supports both Folder-level and Individual Photo-level sharing.
 * Displays:
 * 1. Hellomyphotos Public Link (Copy & QR)
 * 2. Google Drive Link (Copy & QR)
 * If Google Drive Sync is not initialized yet, prompts user to select parameters
 * (Google Account, New vs Existing Drive Folder) and activates sync on demand.
 */
@Composable
fun ShareModalDialog(
    title: String,
    targetPhoto: PhotoItem? = null,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var linksState by remember { mutableStateOf<DualShareLinks?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isGeneratingHellomyphotos by remember { mutableStateOf(false) }

    // QR Code viewer state
    var qrModalUrl by remember { mutableStateOf<String?>(null) }
    var qrModalTitle by remember { mutableStateOf<String?>(null) }

    // GDrive Sync setup state
    var showGDriveSetup by remember { mutableStateOf(false) }
    var googleAccounts by remember { mutableStateOf<List<GoogleAccount>>(emptyList()) }
    var isLoadingAccounts by remember { mutableStateOf(false) }
    var selectedAccount by remember { mutableStateOf<GoogleAccount?>(null) }
    var gdriveFolderMode by remember { mutableStateOf("new") } // "new" or "existing"
    var newFolderName by remember { mutableStateOf(viewModel.sessionId) }
    var remoteFolders by remember { mutableStateOf<List<GDriveFolder>>(emptyList()) }
    var isLoadingRemoteFolders by remember { mutableStateOf(false) }
    var selectedExistingFolder by remember { mutableStateOf<GDriveFolder?>(null) }
    var isSubmittingSync by remember { mutableStateOf(false) }
    var syncErrorMessage by remember { mutableStateOf<String?>(null) }

    fun refreshLinks() {
        isLoading = true
        if (targetPhoto != null) {
            viewModel.fetchPhotoShareLinks(targetPhoto) { links ->
                linksState = links
                isLoading = false
            }
        } else {
            viewModel.fetchFolderShareLinks { links ->
                linksState = links
                isLoading = false
            }
        }
    }

    LaunchedEffect(targetPhoto?.id, viewModel.sessionId) {
        refreshLinks()
    }

    fun handleHellomyphotosAction(openQr: Boolean) {
        val existingUrl = linksState?.hellomyphotosUrl
        if (!existingUrl.isNullOrBlank()) {
            if (openQr) {
                qrModalUrl = existingUrl
                qrModalTitle = "Scan Hellomyphotos Link"
            } else {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Hellomyphotos Link", existingUrl))
                Toast.makeText(context, "Copied Hellomyphotos link!", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Generate on demand when user clicks Copy Link or Show QR
        isGeneratingHellomyphotos = true
        if (targetPhoto != null) {
            viewModel.generatePhotoHellomyphotosLink(targetPhoto) { result ->
                isGeneratingHellomyphotos = false
                result.onSuccess { url ->
                    linksState = linksState?.copy(hellomyphotosUrl = url)
                    if (openQr) {
                        qrModalUrl = url
                        qrModalTitle = "Scan Hellomyphotos Link"
                    } else {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Hellomyphotos Link", url))
                        Toast.makeText(context, "Link generated & copied to clipboard!", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { err ->
                    Toast.makeText(context, "Failed to generate link: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            viewModel.generateFolderHellomyphotosLink { result ->
                isGeneratingHellomyphotos = false
                result.onSuccess { url ->
                    linksState = linksState?.copy(hellomyphotosUrl = url)
                    if (openQr) {
                        qrModalUrl = url
                        qrModalTitle = "Scan Hellomyphotos Link"
                    } else {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Hellomyphotos Link", url))
                        Toast.makeText(context, "Link generated & copied to clipboard!", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { err ->
                    Toast.makeText(context, "Failed to generate link: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun openGDriveSetup() {
        showGDriveSetup = true
        isLoadingAccounts = true
        syncErrorMessage = null
        viewModel.fetchGoogleAccounts { result ->
            isLoadingAccounts = false
            result.onSuccess { accounts ->
                googleAccounts = accounts
                if (accounts.isNotEmpty() && selectedAccount == null) {
                    selectedAccount = accounts.first()
                }
            }.onFailure { err ->
                syncErrorMessage = err.message ?: "Failed to load Google accounts"
            }
        }
    }

    fun loadRemoteFolders(accountId: String) {
        isLoadingRemoteFolders = true
        viewModel.fetchGoogleDriveFolders(accountId) { result ->
            isLoadingRemoteFolders = false
            result.onSuccess { folders ->
                remoteFolders = folders
            }.onFailure { err ->
                syncErrorMessage = err.message ?: "Failed to list remote Drive folders"
            }
        }
    }

    fun submitGDriveSync() {
        val account = selectedAccount
        if (account == null) {
            syncErrorMessage = "Please select a Google Account"
            return
        }

        isSubmittingSync = true
        syncErrorMessage = null

        val folderNameParam = if (gdriveFolderMode == "new") newFolderName.trim().ifBlank { viewModel.sessionId } else null
        val existingFolderIdParam = if (gdriveFolderMode == "existing") selectedExistingFolder?.id else null

        if (gdriveFolderMode == "existing" && existingFolderIdParam.isNullOrBlank()) {
            isSubmittingSync = false
            syncErrorMessage = "Please select an existing Google Drive folder"
            return
        }

        viewModel.initializeGDriveSync(
            accountId = account.id,
            folderName = folderNameParam,
            existingFolderId = existingFolderIdParam
        ) { result ->
            isSubmittingSync = false
            result.onSuccess {
                Toast.makeText(context, "Google Drive sync initialized!", Toast.LENGTH_SHORT).show()
                showGDriveSetup = false
                refreshLinks()
            }.onFailure { err ->
                syncErrorMessage = err.message ?: "Failed to initialize Google Drive sync"
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            border = BorderStroke(1.dp, Slate700)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (targetPhoto != null) "Share Photo" else "Share Folder & Cloud Links",
                            color = Slate100,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = title,
                            color = Slate400,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Slate400)
                    }
                }

                HorizontalDivider(color = Slate800, modifier = Modifier.padding(vertical = 12.dp))

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(color = Blue500, strokeWidth = 3.dp)
                            Text("Fetching share & cloud links...", color = Slate400, fontSize = 13.sp)
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // ==========================================
                        // LINK 1: HELLOMYPHOTOS WEB GALLERY LINK
                        // ==========================================
                        ShareCard(
                            serviceName = "Hellomyphotos Link",
                            serviceIcon = Icons.Default.Public,
                            accentColor = Color(0xFF38BDF8),
                            badgeText = if (!linksState?.hellomyphotosUrl.isNullOrBlank()) (if (targetPhoto != null) "Single Photo" else "Live Gallery") else "On-Demand",
                            url = linksState?.hellomyphotosUrl,
                            isLoading = false,
                            isGenerating = isGeneratingHellomyphotos,
                            emptyMessage = "Link not generated yet. Tap Copy Link or Show QR to generate.",
                            onCopy = { handleHellomyphotosAction(openQr = false) },
                            onShowQr = { handleHellomyphotosAction(openQr = true) }
                        )

                        // ==========================================
                        // LINK 2: GOOGLE DRIVE LINK
                        // ==========================================
                        val gdriveState = linksState?.gdriveStatus ?: "NOT_INITIALIZED"
                        val isGDriveReady = linksState?.isGDriveSynced == true && !linksState?.gdriveUrl.isNullOrBlank()

                        if (isGDriveReady) {
                            ShareCard(
                                serviceName = "Google Drive Link",
                                serviceIcon = Icons.Default.CloudQueue,
                                accentColor = Emerald400,
                                badgeText = if (gdriveState == "PAUSED") "Paused" else "Synced",
                                url = linksState?.gdriveUrl,
                                isLoading = false,
                                onCopy = {
                                    linksState?.gdriveUrl?.let { url ->
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Google Drive Link", url))
                                        Toast.makeText(context, "Copied Google Drive link!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onShowQr = {
                                    linksState?.gdriveUrl?.let { url ->
                                        qrModalUrl = url
                                        qrModalTitle = "Scan Google Drive Link"
                                    }
                                }
                            )
                        } else if (gdriveState == "SYNCING") {
                            // Folder connected, photo is being processed
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Slate800),
                                border = BorderStroke(1.dp, Amber500.copy(alpha = 0.3f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = Amber400,
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Google Drive Sync in Progress",
                                            color = Slate100,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = "Photo is uploading or queued on Google Drive. The direct link will become available shortly.",
                                            color = Slate400,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        } else if (gdriveState == "NOT_UPLOADED") {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Slate800),
                                border = BorderStroke(1.dp, Slate700)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = Slate400,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Photo Not Uploaded Yet",
                                            color = Slate100,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = "Upload this photo to the server first to generate its individual Google Drive link.",
                                            color = Slate400,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        } else {
                            // Google Drive Sync NOT initialized for this folder
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Slate800),
                                border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CloudOff,
                                            contentDescription = null,
                                            tint = Amber400,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Column {
                                            Text(
                                                text = "Google Drive Sync Not Initialized",
                                                color = Slate100,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                            Text(
                                                text = "Google Drive sync is not active for this folder yet.",
                                                color = Slate400,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }

                                    Text(
                                        text = "Initialize Google Drive sync to automatically upload photos to a Google Drive folder and get shareable client links.",
                                        color = Slate300,
                                        fontSize = 12.sp
                                    )

                                    Button(
                                        onClick = { openGDriveSetup() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Amber500),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Set Up Google Drive Sync",
                                            color = Color.Black,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }

                        // ==========================================
                        // INLINE GOOGLE DRIVE SETUP SHEET
                        // ==========================================
                        AnimatedVisibility(
                            visible = showGDriveSetup,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                border = BorderStroke(1.dp, Blue500.copy(alpha = 0.5f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Configure Google Drive Sync",
                                            color = Slate100,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        IconButton(
                                            onClick = { showGDriveSetup = false },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Slate400, modifier = Modifier.size(16.dp))
                                        }
                                    }

                                    if (isLoadingAccounts) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        ) {
                                            CircularProgressIndicator(color = Blue400, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                            Text("Loading connected Google accounts...", color = Slate400, fontSize = 12.sp)
                                        }
                                    } else if (googleAccounts.isEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                                                .padding(12.dp)
                                        ) {
                                            Text(
                                                text = "No Google Accounts connected on the server. Please connect a Google Drive account in Hellomyphotos Admin Settings first.",
                                                color = Rose500,
                                                fontSize = 12.sp
                                            )
                                        }
                                    } else {
                                        // Account Selector
                                        Text(
                                            text = "Select Google Account",
                                            color = Slate300,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )

                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            googleAccounts.forEach { acc ->
                                                val isSelected = selectedAccount?.id == acc.id
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (isSelected) Blue600.copy(alpha = 0.2f) else Slate800)
                                                        .border(1.dp, if (isSelected) Blue500 else Color.Transparent, RoundedCornerShape(8.dp))
                                                        .clickable {
                                                            selectedAccount = acc
                                                            if (gdriveFolderMode == "existing") {
                                                                loadRemoteFolders(acc.id)
                                                            }
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    RadioButton(
                                                        selected = isSelected,
                                                        onClick = {
                                                            selectedAccount = acc
                                                            if (gdriveFolderMode == "existing") {
                                                                loadRemoteFolders(acc.id)
                                                            }
                                                        },
                                                        colors = RadioButtonDefaults.colors(selectedColor = Blue500)
                                                    )
                                                    Column {
                                                        Text(text = acc.email, color = Slate100, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                                        acc.label?.let {
                                                            Text(text = it, color = Slate400, fontSize = 11.sp)
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // Folder Mode Toggle (New vs Existing)
                                        Text(
                                            text = "Destination Drive Folder",
                                            color = Slate300,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Slate800)
                                                .padding(2.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(if (gdriveFolderMode == "new") Blue600 else Color.Transparent)
                                                    .clickable { gdriveFolderMode = "new" }
                                                    .padding(vertical = 6.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "Create New Folder",
                                                    color = if (gdriveFolderMode == "new") Slate100 else Slate400,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(if (gdriveFolderMode == "existing") Blue600 else Color.Transparent)
                                                    .clickable {
                                                        gdriveFolderMode = "existing"
                                                        selectedAccount?.let { loadRemoteFolders(it.id) }
                                                    }
                                                    .padding(vertical = 6.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "Select Existing Folder",
                                                    color = if (gdriveFolderMode == "existing") Slate100 else Slate400,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }

                                        if (gdriveFolderMode == "new") {
                                            OutlinedTextField(
                                                value = newFolderName,
                                                onValueChange = { newFolderName = it },
                                                label = { Text("Google Drive Folder Name", color = Slate400, fontSize = 12.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true,
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedTextColor = Slate100,
                                                    unfocusedTextColor = Slate100,
                                                    focusedBorderColor = Blue500,
                                                    unfocusedBorderColor = Slate700
                                                )
                                            )
                                        } else {
                                            // Existing folder selection list
                                            if (isLoadingRemoteFolders) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    modifier = Modifier.padding(vertical = 8.dp)
                                                ) {
                                                    CircularProgressIndicator(color = Blue400, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                    Text("Fetching folders from Google Drive...", color = Slate400, fontSize = 12.sp)
                                                }
                                            } else if (remoteFolders.isEmpty()) {
                                                Text("No remote folders found in Drive root", color = Slate400, fontSize = 12.sp)
                                            } else {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(max = 140.dp)
                                                        .verticalScroll(rememberScrollState()),
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    remoteFolders.forEach { rf ->
                                                        val isSelected = selectedExistingFolder?.id == rf.id
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .clip(RoundedCornerShape(6.dp))
                                                                .background(if (isSelected) Emerald500.copy(alpha = 0.2f) else Slate800)
                                                                .border(1.dp, if (isSelected) Emerald500 else Color.Transparent, RoundedCornerShape(6.dp))
                                                                .clickable { selectedExistingFolder = rf }
                                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                        ) {
                                                            RadioButton(
                                                                selected = isSelected,
                                                                onClick = { selectedExistingFolder = rf },
                                                                colors = RadioButtonDefaults.colors(selectedColor = Emerald500)
                                                            )
                                                            Text(text = rf.name, color = Slate100, fontSize = 12.sp)
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        syncErrorMessage?.let { err ->
                                            Text(text = err, color = Rose500, fontSize = 12.sp)
                                        }

                                        Button(
                                            onClick = { submitGDriveSync() },
                                            enabled = !isSubmittingSync && selectedAccount != null,
                                            colors = ButtonDefaults.buttonColors(containerColor = Emerald500),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            if (isSubmittingSync) {
                                                CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Initializing Sync...", color = Color.Black)
                                            } else {
                                                Icon(Icons.Default.CloudSync, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Enable & Start Sync", color = Color.Black, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // QR Code Viewer Sub-Dialog
    qrModalUrl?.let { url ->
        QrCodeDialog(
            title = qrModalTitle ?: "Scan Link",
            url = url,
            onDismiss = { qrModalUrl = null }
        )
    }
}

/**
 * Dedicated Share Card for a single service (Hellomyphotos or Google Drive).
 */
@Composable
fun ShareCard(
    serviceName: String,
    serviceIcon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    badgeText: String,
    url: String?,
    isLoading: Boolean = false,
    isGenerating: Boolean = false,
    emptyMessage: String = "Link not available",
    onCopy: () -> Unit,
    onShowQr: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Slate800),
        border = BorderStroke(1.dp, Slate700)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        imageVector = serviceIcon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = serviceName,
                        color = Slate100,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Surface(
                    color = accentColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = accentColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (isLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    CircularProgressIndicator(color = accentColor, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Loading link...", color = Slate400, fontSize = 12.sp)
                }
            } else {
                if (!url.isNullOrBlank()) {
                    // Display URL Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Slate900)
                            .border(1.dp, Slate700, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = url,
                            color = accentColor,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Slate900.copy(alpha = 0.6f))
                            .border(1.dp, Slate700.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = emptyMessage,
                            color = Slate400,
                            fontSize = 12.sp
                        )
                    }
                }

                // Action Buttons: Copy Link & Show QR
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = onCopy,
                        enabled = !isGenerating,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Slate700,
                            contentColor = Slate100
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(color = Slate200, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Generating...", fontSize = 12.sp)
                        } else {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy Link", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Button(
                        onClick = onShowQr,
                        enabled = !isGenerating,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Generating...", fontSize = 12.sp)
                        } else {
                            Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Show QR", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * QR Code Display & Share Dialog.
 */
@Composable
fun QrCodeDialog(
    title: String,
    url: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val qrBitmap = remember(url) {
        QrCodeUtil.generateQrCodeBitmap(url, 512)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            border = BorderStroke(1.dp, Slate700)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        color = Slate100,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Slate400, modifier = Modifier.size(18.dp))
                    }
                }

                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = "QR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Text(
                    text = url,
                    color = Slate400,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Share Link", url))
                            Toast.makeText(context, "Copied link to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Slate800,
                            contentColor = Slate100
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Link", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Link via"))
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue600),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

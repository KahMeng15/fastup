package com.fastup.ui.gallery

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fastup.model.PhotoItem
import com.fastup.model.UploadStatus
import com.fastup.ui.theme.*
import com.fastup.ui.viewmodel.MainViewModel

@Composable
fun GalleryScreen(
    viewModel: MainViewModel,
    onPhotoClick: (String) -> Unit
) {
    val context = LocalContext.current
    val photos by viewModel.photos.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val isUploadPaused by viewModel.isUploadPaused.collectAsState()
    val curationMode by viewModel.curationViewMode.collectAsState()
    val isSyncingCuration by viewModel.isSyncingCuration.collectAsState()
    var showShareFolderDialog by remember { mutableStateOf(false) }

    val pendingCount = remember(photos) { photos.count { it.status == UploadStatus.PENDING || it.status == UploadStatus.UPLOADING } }
    val failedCount = remember(photos) { photos.count { it.status == UploadStatus.FAILED } }
    val uncompletedCount = pendingCount + failedCount

    val displayedPhotos = if (curationMode == "curated") {
        photos.filter { it.isHero || it.burstGroupId == null }
    } else {
        photos
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.syncCuration()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate900)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Media Gallery (${displayedPhotos.size})",
                color = Slate100,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            // Staging View Toggle Pill & Sync Action
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { viewModel.syncCuration() },
                    modifier = Modifier.size(28.dp)
                ) {
                    if (isSyncingCuration) {
                        CircularProgressIndicator(
                            color = Amber400,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Sync Curation from Server",
                            tint = Slate400,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF1E293B))
                        .padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = if (curationMode == "curated") Amber500.copy(alpha = 0.25f) else Color.Transparent,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.clickable { viewModel.setCurationViewMode("curated") }
                    ) {
                        Text(
                            text = "★ Curated",
                            color = if (curationMode == "curated") Amber400 else Slate400,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Surface(
                        color = if (curationMode == "all") Slate700 else Color.Transparent,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.clickable { viewModel.setCurationViewMode("all") }
                    ) {
                        Text(
                            text = "All (${photos.size})",
                            color = if (curationMode == "all") Slate100 else Slate400,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // Lower Sub-header: Folder Name, Retry Upload All & Share Link Action
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E293B).copy(alpha = 0.6f))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = Amber400,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = viewModel.sessionId.ifBlank { "Root" },
                    color = Slate200,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Retry Upload All Button
                FilledTonalButton(
                    onClick = {
                        val count = viewModel.retryAll(includeCompleted = uncompletedCount == 0)
                        val msg = if (count > 0) {
                            "Retrying $count uploads (including pending)..."
                        } else {
                            "All photos already uploaded"
                        }
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = when {
                            failedCount > 0 -> Rose500.copy(alpha = 0.22f)
                            uncompletedCount > 0 -> Amber500.copy(alpha = 0.22f)
                            else -> Emerald500.copy(alpha = 0.22f)
                        },
                        contentColor = when {
                            failedCount > 0 -> Rose400
                            uncompletedCount > 0 -> Amber400
                            else -> Emerald400
                        }
                    ),
                    modifier = Modifier.height(26.dp)
                ) {
                    Icon(
                        imageVector = if (isUploading) Icons.Default.Sync else Icons.Default.CloudUpload,
                        contentDescription = "Retry Upload All",
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = when {
                            isUploading -> "Uploading..."
                            uncompletedCount > 0 -> "Retry All ($uncompletedCount)"
                            else -> "Retry All"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                FilledTonalButton(
                    onClick = { showShareFolderDialog = true },
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Blue600.copy(alpha = 0.25f),
                        contentColor = Blue400
                    ),
                    modifier = Modifier.height(26.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share Folder", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Uncompleted Uploads Status Strip (visible when pending or failed items exist)
        if (uncompletedCount > 0) {
            Surface(
                color = if (failedCount > 0) Rose500.copy(alpha = 0.15f) else Color(0xFF1E293B),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, if (failedCount > 0) Rose500.copy(alpha = 0.4f) else Color(0xFF334155)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (failedCount > 0) Icons.Default.Warning else Icons.Default.CloudQueue,
                            contentDescription = null,
                            tint = if (failedCount > 0) Rose400 else Amber400,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = buildString {
                                if (failedCount > 0) append("$failedCount failed")
                                if (failedCount > 0 && pendingCount > 0) append(" • ")
                                if (pendingCount > 0) append("$pendingCount pending")
                                if (isUploadPaused) append(" (Paused)")
                            },
                            color = if (failedCount > 0) Rose400 else Slate200,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    TextButton(
                        onClick = {
                            val count = viewModel.retryAll(includeCompleted = false)
                            android.widget.Toast.makeText(
                                context,
                                "Retrying $count uploads (including pending)...",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text(
                            text = "Retry All Now",
                            color = if (failedCount > 0) Rose400 else Blue400,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (displayedPhotos.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = Slate600,
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        text = "No photos captured yet",
                        color = Slate400,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Take a photo with the camera physical shutter to view it here instantly.",
                        color = Slate600,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            val configuration = LocalConfiguration.current
            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val columnsCount = if (isLandscape) 5 else 3

            LazyVerticalGrid(
                columns = GridCells.Fixed(columnsCount),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(displayedPhotos, key = { it.id }) { photo ->
                    PhotoGridItem(
                        photo = photo,
                        onClick = { onPhotoClick(photo.id) },
                        onBurstClick = {
                            viewModel.openBurstDrawer(photo)
                        }
                    )
                }
            }
        }
    }

    // Burst Sequence Stack Drawer (hellomyphotos aligned)
    BurstStackDrawer(
        viewModel = viewModel,
        onPhotoSelect = { photoId ->
            onPhotoClick(photoId)
        }
    )

    if (showShareFolderDialog) {
        com.fastup.ui.share.ShareModalDialog(
            title = "Folder: ${viewModel.sessionId}",
            targetPhoto = null,
            viewModel = viewModel,
            onDismiss = { showShareFolderDialog = false }
        )
    }
}

@Composable
fun PhotoGridItem(
    photo: PhotoItem,
    onClick: () -> Unit,
    onBurstClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Slate800)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = photo.displayFile,
            contentDescription = photo.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Format Badge (RAW / JPG / RAW+JPG)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            val badgeText = when {
                photo.rawFile != null && photo.jpegFile != null -> "RAW+JPG"
                photo.isRaw -> "RAW"
                else -> "JPG"
            }
            Text(
                text = badgeText,
                color = if (photo.isRaw) Amber500 else Slate100,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Burst Count Badge with clickable stack icon
        if (photo.burstCount > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xE60F172A))
                    .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .clickable { onBurstClick?.invoke() }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = "View Burst Sequence",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "${photo.burstCount}",
                        color = Slate100,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Status Badge
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .size(22.dp)
                .background(
                    when (photo.status) {
                        UploadStatus.COMPLETED -> Emerald500
                        UploadStatus.UPLOADING -> Blue500
                        UploadStatus.PENDING -> Amber500
                        UploadStatus.FAILED -> Rose500
                    },
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            when (photo.status) {
                UploadStatus.COMPLETED -> Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                UploadStatus.UPLOADING -> CircularProgressIndicator(
                    progress = { photo.uploadProgress },
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp)
                )
                UploadStatus.PENDING -> Icon(Icons.Default.Schedule, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                UploadStatus.FAILED -> Icon(Icons.Default.PriorityHigh, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

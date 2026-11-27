package com.fastup.ui.gallery

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
    var shareFolderTarget by remember { mutableStateOf<String?>(null) }

    val pendingCount = remember(photos) { photos.count { it.status == UploadStatus.PENDING || it.status == UploadStatus.UPLOADING } }
    val failedCount = remember(photos) { photos.count { it.status == UploadStatus.FAILED } }
    val uncompletedCount = pendingCount + failedCount

    val favoritesCount = photos.count { it.isFavorite }
    val displayedPhotos = when (curationMode) {
        "favorites" -> photos.filter { it.isFavorite }
        "curated" -> photos.filter { it.isHero || it.burstGroupId == null }
        else -> photos
    }

    // Group photos by their folder name.
    // Order folders so the most recently used / uploaded folder is at the top,
    // and older folders follow below.
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val folderGroups = remember(displayedPhotos, currentSessionId) {
        val grouped = displayedPhotos.groupBy { it.folderName }
        grouped.entries.sortedWith(
            compareByDescending<Map.Entry<String, List<PhotoItem>>> { entry ->
                // Prioritize active folder first
                if (entry.key.equals(currentSessionId, ignoreCase = true)) 1 else 0
            }.thenByDescending { entry ->
                // Sort by the latest photo timestamp or upload activity in that folder
                entry.value.maxOfOrNull { it.timestamp } ?: 0L
            }
        )
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Curated (Auto-Awesome / Magic Wand)
                    Surface(
                        color = if (curationMode == "curated") Amber500.copy(alpha = 0.25f) else Color.Transparent,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.clickable { viewModel.setCurationViewMode("curated") }
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Curated",
                                tint = if (curationMode == "curated") Amber400 else Slate400,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // All Photos (Photo Library / Collections)
                    Surface(
                        color = if (curationMode == "all") Slate700 else Color.Transparent,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.clickable { viewModel.setCurationViewMode("all") }
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = "All Photos (${photos.size})",
                                tint = if (curationMode == "all") Slate100 else Slate400,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Favorites (Heart / Favorite)
                    Surface(
                        color = if (curationMode == "favorites") Color(0x33EF4444) else Color.Transparent,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.clickable { viewModel.setCurationViewMode("favorites") }
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = "Favorites ($favoritesCount)",
                                tint = if (curationMode == "favorites") Color(0xFFEF4444) else Slate400,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Global Uncompleted Uploads Status Strip (visible when pending or failed items exist across any folder)
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
                        imageVector = if (curationMode == "favorites") Icons.Default.FavoriteBorder else Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = if (curationMode == "favorites") Color(0xFFEF4444).copy(alpha = 0.6f) else Slate600,
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        text = when (curationMode) {
                            "favorites" -> "No favorites across folders"
                            "curated" -> "No curated photos"
                            else -> "No photos captured yet"
                        },
                        color = Slate400,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = when (curationMode) {
                            "favorites" -> "Tap the heart icon on any photo to favorite it and sync directly with hellomyphotos."
                            else -> "Take a photo with the camera physical shutter to view it here instantly."
                        },
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
                folderGroups.forEach { (folderName, folderPhotos) ->
                    val folderFailed = folderPhotos.count { it.status == UploadStatus.FAILED }
                    val folderPending = folderPhotos.count { it.status == UploadStatus.PENDING || it.status == UploadStatus.UPLOADING }
                    val folderUncompleted = folderFailed + folderPending
                    val isCurrentActive = folderName.equals(currentSessionId, ignoreCase = true)

                    // Folder Header Strip (Spans full width)
                    item(span = { GridItemSpan(columnsCount) }, key = "header_$folderName") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, bottom = 6.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isCurrentActive) Color(0xFF1E293B) else Color(0xFF1E293B).copy(alpha = 0.6f)
                                )
                                .border(
                                    1.dp,
                                    if (isCurrentActive) Amber500.copy(alpha = 0.4f) else Color(0xFF334155),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 7.dp),
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
                                    tint = if (isCurrentActive) Amber400 else Slate400,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = folderName,
                                    color = if (isCurrentActive) Amber400 else Slate200,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                if (isCurrentActive) {
                                    Surface(
                                        color = Amber500.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(
                                            text = "Active",
                                            color = Amber400,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "(${folderPhotos.size})",
                                    color = Slate400,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Retry uploads for this folder
                                FilledTonalButton(
                                    onClick = {
                                        val count = viewModel.retryPhotos(folderPhotos, includeCompleted = folderUncompleted == 0)
                                        val msg = if (count > 0) {
                                            "Retrying $count upload(s) in '$folderName'..."
                                        } else {
                                            "All photos in '$folderName' already uploaded"
                                        }
                                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = when {
                                            folderFailed > 0 -> Rose500.copy(alpha = 0.22f)
                                            folderUncompleted > 0 -> Amber500.copy(alpha = 0.22f)
                                            else -> Emerald500.copy(alpha = 0.22f)
                                        },
                                        contentColor = when {
                                            folderFailed > 0 -> Rose400
                                            folderUncompleted > 0 -> Amber400
                                            else -> Emerald400
                                        }
                                    ),
                                    modifier = Modifier.height(26.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isUploading && folderUncompleted > 0) Icons.Default.Sync else Icons.Default.CloudUpload,
                                        contentDescription = "Retry Upload",
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = when {
                                            isUploading && folderUncompleted > 0 -> "Uploading..."
                                            folderUncompleted > 0 -> "Retry ($folderUncompleted)"
                                            else -> "Retry"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // Share Folder link button
                                FilledTonalButton(
                                    onClick = { shareFolderTarget = folderName },
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
                                    Text("Share", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // Photos in this folder
                    items(folderPhotos, key = { it.id }) { photo ->
                        PhotoGridItem(
                            photo = photo,
                            onClick = { onPhotoClick(photo.id) },
                            onBurstClick = {
                                viewModel.openBurstDrawer(photo)
                            },
                            onFavoriteClick = {
                                viewModel.toggleFavorite(photo)
                            }
                        )
                    }
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

    if (shareFolderTarget != null) {
        com.fastup.ui.share.ShareModalDialog(
            title = "Folder: $shareFolderTarget",
            targetPhoto = null,
            viewModel = viewModel,
            onDismiss = { shareFolderTarget = null }
        )
    }
}

@Composable
fun PhotoGridItem(
    photo: PhotoItem,
    onClick: () -> Unit,
    onBurstClick: (() -> Unit)? = null,
    onFavoriteClick: (() -> Unit)? = null
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

        // Favorite Heart Button (TopEnd)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    if (photo.isFavorite) Color.Black.copy(alpha = 0.65f)
                    else Color.Black.copy(alpha = 0.35f)
                )
                .clickable { onFavoriteClick?.invoke() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (photo.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (photo.isFavorite) "Remove from favorites" else "Add to favorites",
                tint = if (photo.isFavorite) Color(0xFFEF4444) else Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(16.dp)
            )
        }

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

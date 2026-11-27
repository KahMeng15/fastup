package com.fastup.ui.gallery

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fastup.model.UploadStatus
import com.fastup.ui.theme.*
import com.fastup.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoDetailScreen(
    photoId: String,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val photos by viewModel.photos.collectAsState()
    val photo = photos.find { it.id == photoId }
    var showShareDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (photo == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Photo not found", color = Slate400)
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(photo.fileName, color = Slate100, fontSize = 16.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Slate100)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleFavorite(photo) }) {
                        Icon(
                            imageVector = if (photo.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (photo.isFavorite) "Remove from favorites" else "Add to favorites",
                            tint = if (photo.isFavorite) Color(0xFFEF4444) else Slate400
                        )
                    }
                    IconButton(onClick = { showShareDialog = true }) {
                        Icon(Icons.Default.Share, contentDescription = "Share Links & QR", tint = Color(0xFF38BDF8))
                    }
                    IconButton(onClick = { viewModel.retryPhoto(photo.id) }) {
                        Icon(Icons.Default.Upload, contentDescription = "Upload", tint = Blue500)
                    }
                    if (photo.burstGroupId != null || photo.burstCount > 1) {
                        IconButton(onClick = { viewModel.openBurstDrawer(photo) }) {
                            Icon(Icons.Default.Layers, contentDescription = "View Burst Stack", tint = Color(0xFF38BDF8))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Slate900)
            )
        },
        containerColor = Slate900
    ) { padding ->
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = photo.displayFile,
                        contentDescription = photo.fileName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    PhotoMetadataCard(
                        photo = photo,
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Full Screen Image Preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = photo.displayFile,
                        contentDescription = photo.fileName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                PhotoMetadataCard(
                    photo = photo,
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }
        }

        BurstStackDrawer(
            viewModel = viewModel,
            onPhotoSelect = { /* Already in detail screen */ }
        )

        if (showShareDialog) {
            com.fastup.ui.share.ShareModalDialog(
                title = photo.fileName,
                targetPhoto = photo,
                viewModel = viewModel,
                onDismiss = { showShareDialog = false }
            )
        }
    }
}

@Composable
fun PhotoMetadataCard(
    photo: com.fastup.model.PhotoItem,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
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
                text = "Camera & EXIF Data",
                color = Slate100,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ExifParamItem(label = "Shutter", value = photo.exif.shutterSpeed ?: "--")
                ExifParamItem(label = "Aperture", value = photo.exif.aperture ?: "--")
                ExifParamItem(label = "ISO", value = photo.exif.iso ?: "--")
                ExifParamItem(label = "Focal", value = photo.exif.focalLength ?: "--")
            }

            HorizontalDivider(color = Slate700, thickness = 1.dp)

            photo.exif.lensModel?.let { lens ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Lens", color = Slate400, fontSize = 13.sp)
                    Text(lens, color = Slate100, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Camera", color = Slate400, fontSize = 13.sp)
                Text(photo.exif.cameraModel ?: "Sony ILCE-7M3", color = Slate100, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("File Size", color = Slate400, fontSize = 13.sp)
                Text(
                    String.format("%.2f MB", photo.fileSize.toDouble() / (1024 * 1024)),
                    color = Slate100,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Upload Status", color = Slate400, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        photo.status.name,
                        color = when (photo.status) {
                            UploadStatus.COMPLETED -> Emerald500
                            UploadStatus.UPLOADING -> Blue500
                            UploadStatus.PENDING -> Amber500
                            UploadStatus.FAILED -> Rose500
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    if (photo.status != UploadStatus.UPLOADING) {
                        OutlinedButton(
                            onClick = {
                                viewModel.retryPhoto(photo.id)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (photo.status == UploadStatus.PENDING) Amber500 else Blue400
                            ),
                            border = BorderStroke(1.dp, if (photo.status == UploadStatus.PENDING) Amber500 else Blue400)
                        ) {
                            Icon(
                                imageVector = if (photo.status == UploadStatus.PENDING) Icons.Default.CloudUpload else Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (photo.status == UploadStatus.PENDING) "Upload Now" else "Retry",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (photo.burstGroupId != null || photo.burstCount > 1) {
                HorizontalDivider(color = Slate700, thickness = 1.dp)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("AI Curation Role", color = Slate400, fontSize = 13.sp)
                    Text(
                        if (photo.isHero) "★ Primary Pick (Hero)" else "Alternate Shot",
                        color = if (photo.isHero) Amber400 else Slate300,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Burst Sequence Size", color = Slate400, fontSize = 13.sp)
                    Text(
                        "${photo.burstCount} shots",
                        color = Slate100,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                photo.qualityScore?.let { score ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Quality / Sharpness", color = Slate400, fontSize = 13.sp)
                        Text(
                            String.format("%.1f", score),
                            color = Slate100,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Button(
                    onClick = { viewModel.openBurstDrawer(photo) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF38BDF8).copy(alpha = 0.15f),
                        contentColor = Color(0xFF7DD3FC)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "View Burst Stack (${photo.burstCount} shots)",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }

            HorizontalDivider(color = Slate700, thickness = 1.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (photo.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        tint = if (photo.isFavorite) Color(0xFFEF4444) else Slate400,
                        modifier = Modifier.size(16.dp)
                    )
                    Text("Favorite Status", color = Slate400, fontSize = 13.sp)
                }

                Surface(
                    color = if (photo.isFavorite) Color(0x33EF4444) else Color(0x1F94A3B8),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(
                        1.dp,
                        if (photo.isFavorite) Color(0x80EF4444) else Color(0x3394A3B8)
                    ),
                    modifier = Modifier.clickable { viewModel.toggleFavorite(photo) }
                ) {
                    Text(
                        text = if (photo.isFavorite) "❤️ Favorited" else "Add to Favorites",
                        color = if (photo.isFavorite) Color(0xFFEF4444) else Slate300,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            photo.errorMessage?.let { err ->
                Text("Error: $err", color = Rose500, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun ExifParamItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = Slate100, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(text = label, color = Slate400, fontSize = 11.sp)
    }
}

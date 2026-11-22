package com.fastup.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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

            // Metadata & EXIF Details Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
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

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Upload Status", color = Slate400, fontSize = 13.sp)
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

                    photo.errorMessage?.let { err ->
                        Text("Error: $err", color = Rose500, fontSize = 12.sp)
                    }
                }
            }
        }

        BurstStackDrawer(
            viewModel = viewModel,
            onPhotoSelect = { /* Already in detail screen */ }
        )
    }
}

@Composable
fun ExifParamItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = Slate100, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(text = label, color = Slate400, fontSize = 11.sp)
    }
}

package com.fastup.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fastup.model.PhotoItem
import com.fastup.model.ServerStagingPhoto
import com.fastup.ui.theme.*
import com.fastup.ui.viewmodel.MainViewModel

// hellomyphotos Theme Matches:
// Active hero border: #38bdf8 (Sky 400)
// Hero badge: #7dd3fc, background: rgba(56, 189, 248, 0.12)
// Curated / Shown: #4ade80 (Green 400), background: rgba(34, 197, 94, 0.18), border: rgba(34, 197, 94, 0.4)
// Hidden: #94a3b8 (Slate 400), background: rgba(148, 163, 184, 0.12), border: rgba(148, 163, 184, 0.25)
private val Sky400 = Color(0xFF38BDF8)
private val Sky300 = Color(0xFF7DD3FC)
private val Green400 = Color(0xFF4ADE80)
private val GreenBg = Color(0x2E22C55E)
private val GreenBorder = Color(0x6622C55E)
private val HiddenBg = Color(0x1F94A3B8)
private val HiddenBorder = Color(0x4094A3B8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BurstStackDrawer(
    viewModel: MainViewModel,
    onPhotoSelect: ((String) -> Unit)? = null
) {
    val selectedBurstGroupId by viewModel.selectedBurstGroupId.collectAsState()
    val alternates by viewModel.burstAlternates.collectAsState()
    val isLoading by viewModel.isLoadingBurstAlternates.collectAsState()
    val allPhotos by viewModel.photos.collectAsState()

    if (selectedBurstGroupId != null) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeBurstDrawer() },
            containerColor = Color(0xFF0F172A), // Dark slate matching hellomyphotos
            tonalElevation = 8.dp,
            scrimColor = Color.Black.copy(alpha = 0.65f),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = null,
                                tint = Sky400,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Burst Stack",
                                color = Slate100,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "${alternates.size} photos in burst sequence",
                            color = Slate400,
                            fontSize = 12.sp
                        )
                    }

                    IconButton(
                        onClick = { viewModel.closeBurstDrawer() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Slate400,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Sky400, strokeWidth = 2.dp)
                    }
                } else if (alternates.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No burst alternates found", color = Slate400, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(alternates, key = { _, alt -> alt.id }) { index, alt ->
                            val localPhoto = allPhotos.find {
                                it.serverMediaId == alt.id ||
                                    it.id == alt.id ||
                                    it.fileName == alt.fileName ||
                                    it.fileName.substringBeforeLast(".") == alt.fileName.substringBeforeLast(".")
                            }

                            BurstStackItemCard(
                                alt = alt,
                                index = index + 1,
                                localPhoto = localPhoto,
                                onSetHero = {
                                    viewModel.setHeroPhoto(alt.id)
                                },
                                onToggleVisibility = {
                                    viewModel.togglePhotoVisibility(alt.id, alt.isHero)
                                },
                                onClick = {
                                    if (localPhoto != null && onPhotoSelect != null) {
                                        viewModel.closeBurstDrawer()
                                        onPhotoSelect(localPhoto.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BurstStackItemCard(
    alt: ServerStagingPhoto,
    index: Int,
    localPhoto: PhotoItem?,
    onSetHero: () -> Unit,
    onToggleVisibility: () -> Unit,
    onClick: () -> Unit
) {
    val isHero = alt.isHero

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(
                width = 1.dp,
                color = if (isHero) Sky400 else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail with # Index badge
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black)
        ) {
            if (localPhoto != null) {
                AsyncImage(
                    model = localPhoto.displayFile,
                    contentDescription = alt.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = Slate600,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(2.dp)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "#$index",
                    color = Slate300,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Details & Action buttons
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = alt.fileName,
                    color = Slate200,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (alt.qualityScore != null) {
                    Text(
                        text = "Score: ${String.format("%.1f", alt.qualityScore)}",
                        color = Slate400,
                        fontSize = 11.sp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Curated / Hidden Visibility Toggle Pill
                Surface(
                    color = if (isHero) GreenBg else HiddenBg,
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = if (isHero) GreenBorder else HiddenBorder
                    ),
                    modifier = Modifier.clickable(onClick = onToggleVisibility)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (isHero) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = null,
                            tint = if (isHero) Green400 else Slate400,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = if (isHero) "Curated" else "Hidden",
                            color = if (isHero) Green400 else Slate400,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Set Best Hero Action Button (if not already hero)
                if (!isHero) {
                    Surface(
                        color = Sky400.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(4.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = Sky400.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.clickable(onClick = onSetHero)
                    ) {
                        Text(
                            text = "Set Best",
                            color = Sky300,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = Amber400,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "Primary Pick",
                            color = Amber400,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

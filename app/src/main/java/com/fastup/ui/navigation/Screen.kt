package com.fastup.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Dashboard)
    object Gallery : Screen("gallery", "Gallery", Icons.Default.PhotoLibrary)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    object Detail : Screen("detail/{photoId}", "Detail", Icons.Default.PhotoLibrary) {
        fun createRoute(photoId: String) = "detail/$photoId"
    }
}

package com.fastup.model

data class GoogleAccount(
    val id: String,
    val email: String,
    val label: String? = null
)

data class GDriveFolder(
    val id: String,
    val name: String,
    val mimeType: String = "application/vnd.google-apps.folder"
)

data class FolderGDriveSync(
    val id: String,
    val accountId: String,
    val folderPath: String,
    val gdriveFolderId: String,
    val gdriveFolderName: String,
    val gdriveLink: String,
    val syncedFileCount: Int = 0,
    val totalLocalJpegs: Int = 0,
    val syncStatus: String = "IDLE",
    val isActive: Boolean = true
)

data class FolderSyncInfo(
    val synced: Boolean,
    val sync: FolderGDriveSync? = null
)

data class MediaGDriveLinkResult(
    val synced: Boolean,
    val link: String? = null,
    val gdriveFileId: String? = null
)

data class DualShareLinks(
    val title: String,
    val hellomyphotosUrl: String? = null,
    val hellomyphotosFavoritesUrl: String? = null,
    val gdriveUrl: String? = null,
    val isGDriveSynced: Boolean = false,
    val gdriveStatus: String = "NOT_INITIALIZED", // NOT_INITIALIZED, SYNCING, READY, NOT_SYNCED
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

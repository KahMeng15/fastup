package com.fastup.model

import java.io.File

enum class UploadStatus {
    PENDING,
    UPLOADING,
    COMPLETED,
    FAILED
}

enum class UploadFormat(val label: String) {
    RAW_AND_JPEG("RAW + JPEG"),
    JPEG_ONLY("JPEG only"),
    RAW_ONLY("RAW only")
}

enum class CameraCaptureFormat(val label: String) {
    RAW_AND_JPEG("RAW + JPEG"),
    JPEG_ONLY("JPEG only"),
    RAW_ONLY("RAW only")
}

enum class CameraConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    CAPTURING,
    ERROR
}

data class ExifDetails(
    val shutterSpeed: String? = null,
    val aperture: String? = null,
    val iso: String? = null,
    val focalLength: String? = null,
    val lensModel: String? = null,
    val cameraModel: String? = "Sony ILCE-7M3"
)

data class PhotoItem(
    val id: String,
    val fileName: String,
    val localFile: File,
    val rawFile: File? = null,
    val jpegFile: File? = null,
    val previewFile: File? = null,
    val fileSize: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val status: UploadStatus = UploadStatus.PENDING,
    val uploadProgress: Float = 0f,
    val uploadSpeedBps: Long = 0L,
    val errorMessage: String? = null,
    val exif: ExifDetails = ExifDetails(),
    val burstGroupId: String? = null,
    val isHero: Boolean = true,
    val burstCount: Int = 1,
    val qualityScore: Float? = null
) {
    val displayFile: File
        get() = previewFile ?: jpegFile ?: localFile

    val isRaw: Boolean
        get() = fileName.endsWith(".arw", ignoreCase = true) || rawFile != null

    val isJpeg: Boolean
        get() = fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true) || jpegFile != null
}

data class ShareSessionInfo(
    val sessionId: String,
    val shareUrl: String,
    val createdAt: Long = System.currentTimeMillis()
)

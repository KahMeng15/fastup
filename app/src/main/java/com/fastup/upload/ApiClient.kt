package com.fastup.upload

import android.util.Log
import com.fastup.model.ShareSessionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ClaimOtpResult(
    val token: String,
    val photographerName: String,
    val folderPath: String,
    val serverUrl: String,
    val uploadFormat: String,
    val autoUpload: Boolean,
    val wifiOnly: Boolean
)

class ApiClient(
    var baseUrl: String = "http://10.0.0.43:8000",
    var apiKey: String = "fastup_secret_key"
) {

    companion object {
        /**
         * Resolves the device's actual Wi-Fi / LAN IP address (e.g. 10.0.0.44).
         */
        fun getLocalIpAddress(): String? {
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            val host = addr.hostAddress ?: ""
                            if (host.isNotBlank() && !host.startsWith("127.")) {
                                return host
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            return null
        }
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val original = chain.request()
            val baseOrigin = cleanUrl("").removeSuffix("/")
            val proto = if (baseOrigin.startsWith("https", ignoreCase = true)) "https" else "http"
            val localIp = getLocalIpAddress()
            val requestBuilder = original.newBuilder()
                .header("x-forwarded-proto", proto)
                .header("Origin", baseOrigin)
            if (!localIp.isNullOrBlank()) {
                requestBuilder.header("x-device-ip", localIp)
                requestBuilder.header("x-client-ip", localIp)
            }
            chain.proceed(requestBuilder.build())
        }
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var activeUploadCall: okhttp3.Call? = null

    fun cancelCurrentUpload() {
        try {
            activeUploadCall?.cancel()
        } catch (_: Exception) {}
        activeUploadCall = null
    }

    private fun cleanUrl(endpoint: String): String {
        val base = baseUrl.trimEnd('/')
        val path = endpoint.trimStart('/')
        return "$base/$path"
    }

    /**
     * Upload an image file to staging API.
     * POST /api/staging/upload
     */
    suspend fun uploadPhoto(
        file: File,
        sessionId: String,
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val validSessionId = sessionId.trim().ifEmpty { "weddings" }
            val uploadFileName = if (file.name.endsWith("_thumb.jpg", ignoreCase = true)) {
                file.name.replace(Regex("_thumb\\.jpg$", RegexOption.IGNORE_CASE), ".jpg")
            } else {
                file.name
            }
            val mediaType = if (uploadFileName.endsWith(".arw", ignoreCase = true)) {
                "application/octet-stream".toMediaTypeOrNull()
            } else {
                "image/jpeg".toMediaTypeOrNull()
            }

            val fileRequestBody = file.asRequestBody(mediaType)
            val progressBody = ProgressRequestBody(fileRequestBody, onProgress)

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("sessionId", validSessionId)
                .addFormDataPart("session_id", validSessionId)
                .addFormDataPart("folderPath", validSessionId)
                .addFormDataPart(
                    "file",
                    uploadFileName,
                    progressBody
                )
                .build()

            val encodedSessionId = java.net.URLEncoder.encode(validSessionId, "UTF-8")
            val baseOrigin = cleanUrl("").removeSuffix("/")
            val proto = if (baseOrigin.startsWith("https", ignoreCase = true)) "https" else "http"
            val request = Request.Builder()
                .url(cleanUrl("api/staging/upload?sessionId=$encodedSessionId"))
                .addHeader("x-api-key", apiKey)
                .addHeader("x-staging-key", apiKey)
                .addHeader("x-photographer-token", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("x-session-id", validSessionId)
                .addHeader("x-forwarded-proto", proto)
                .addHeader("Origin", baseOrigin)
                .post(multipartBody)
                .build()

            val call = client.newCall(request)
            activeUploadCall = call
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val err = response.body?.string() ?: "HTTP ${response.code}"
                        Result.failure(IOException("Upload failed (${response.code}): $err"))
                    } else {
                        val respStr = response.body?.string() ?: ""
                        Result.success(respStr)
                    }
                }
            } finally {
                activeUploadCall = null
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Error uploading photo ${file.name}", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch list of existing staging folders.
     * GET /api/staging/folders
     */
    suspend fun fetchStagingFolders(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(cleanUrl("api/staging/folders"))
                .addHeader("x-api-key", apiKey)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Result.failure(IOException("Failed to fetch folders: HTTP ${response.code}"))
                } else {
                    val bodyStr = response.body?.string() ?: "{}"
                    val folders = mutableListOf<String>()
                    val obj = try { JSONObject(bodyStr) } catch (_: Exception) { null }
                    val array = if (obj != null && obj.has("folders")) {
                        obj.getJSONArray("folders")
                    } else if (bodyStr.trim().startsWith("[")) {
                        org.json.JSONArray(bodyStr)
                    } else null

                    if (array != null) {
                        for (i in 0 until array.length()) {
                            val item = array.opt(i)
                            if (item is JSONObject) {
                                val path = item.optString("path", item.optString("folderPath", item.optString("name", "")))
                                if (path.isNotBlank()) folders.add(path)
                            } else if (item is String && item.isNotBlank()) {
                                folders.add(item)
                            }
                        }
                    }
                    Result.success(folders)
                }
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Error fetching staging folders", e)
            Result.failure(e)
        }
    }

    /**
     * Create a new staging folder on the server.
     * POST /api/staging/folders with { "folderPath": folderPath }
     */
    suspend fun createStagingFolder(folderPath: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanPath = folderPath.trim().trim('/')
            val json = JSONObject().apply {
                put("folderPath", cleanPath)
            }
            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val baseOrigin = cleanUrl("").removeSuffix("/")
            val request = Request.Builder()
                .url(cleanUrl("api/staging/folders"))
                .addHeader("x-api-key", apiKey)
                .addHeader("Origin", baseOrigin)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string() ?: "HTTP ${response.code}"
                    Result.failure(IOException("Failed creating folder: $err"))
                } else {
                    val bodyStr = response.body?.string() ?: "{}"
                    val obj = try { JSONObject(bodyStr) } catch (_: Exception) { null }
                    val created = obj?.optString("folderPath", cleanPath) ?: cleanPath
                    Result.success(created)
                }
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Error creating staging folder", e)
            Result.failure(e)
        }
    }

    /**
     * Create on-demand share link for staging session.
     * POST /api/staging/create-share
     */
    suspend fun createShareLink(sessionId: String, expiresInHours: Int = 24): Result<ShareSessionInfo> =
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("session_id", sessionId)
                    put("expires_in_hours", expiresInHours)
                }

                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url(cleanUrl("api/staging/create-share"))
                    .addHeader("x-api-key", apiKey)
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Result.failure(IOException("Failed to create share link: HTTP ${response.code}"))
                    } else {
                        val bodyStr = response.body?.string() ?: "{}"
                        val obj = JSONObject(bodyStr)
                        val shareUrl = when {
                            obj.has("share_url") -> obj.getString("share_url")
                            obj.has("url") -> obj.getString("url")
                            else -> "${baseUrl.trimEnd('/')}/share/$sessionId"
                        }
                        Result.success(ShareSessionInfo(sessionId = sessionId, shareUrl = shareUrl))
                    }
                }
            } catch (e: Exception) {
                Log.e("ApiClient", "Error creating share link", e)
                Result.failure(e)
            }
        }

    /**
     * Cleanup staging photos for a session.
     * DELETE /api/staging/:sessionId
     */
    suspend fun cleanupSession(sessionId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(cleanUrl("api/staging/$sessionId"))
                .addHeader("x-api-key", apiKey)
                .delete()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(IOException("Cleanup failed: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Error cleaning up session $sessionId", e)
            Result.failure(e)
        }
    }

    /**
     * Claim 6-digit OTP code to receive scoped photographer token and predefined settings.
     * POST /api/staging/otp/claim
     */
    suspend fun claimOtp(targetServerUrl: String, otpCode: String): Result<ClaimOtpResult> = withContext(Dispatchers.IO) {
        try {
            val base = targetServerUrl.trimEnd('/')
            val endpoint = "$base/api/staging/otp/claim"
            val json = JSONObject().apply {
                put("otp", otpCode.trim())
                val localIp = getLocalIpAddress()
                if (!localIp.isNullOrBlank()) {
                    put("clientIp", localIp)
                }
            }
            val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(endpoint)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val respStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val err = try {
                        JSONObject(respStr).optString("error", respStr)
                    } catch (_: Exception) {
                        respStr
                    }
                    Result.failure(IOException(if (err.isNotBlank()) err else "Pairing failed (${response.code})"))
                } else {
                    val jsonObj = JSONObject(respStr)
                    val token = jsonObj.getString("token")
                    val photographerName = jsonObj.optString("photographerName", "Photographer")
                    val folderPath = jsonObj.optString("folderPath", "weddings")
                    val settings = jsonObj.optJSONObject("settings")
                    val sUrl = settings?.optString("serverUrl")?.ifBlank { targetServerUrl } ?: targetServerUrl
                    val sFormat = settings?.optString("uploadFormat", "JPEG_ONLY") ?: "JPEG_ONLY"
                    val sAuto = settings?.optBoolean("autoUpload", true) ?: true
                    val sWifi = settings?.optBoolean("wifiOnly", false) ?: false

                    Result.success(
                        ClaimOtpResult(
                            token = token,
                            photographerName = photographerName,
                            folderPath = folderPath,
                            serverUrl = sUrl,
                            uploadFormat = sFormat,
                            autoUpload = sAuto,
                            wifiOnly = sWifi
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Error claiming OTP code", e)
            Result.failure(e)
        }
    }

    /**
     * Probe server storage availability.
     */
    suspend fun fetchServerStorageAvailable(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(cleanUrl("api/staging/sessions"))
                .addHeader("x-api-key", apiKey)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    val obj = try { JSONObject(bodyStr) } catch (_: Exception) { null }
                    val avail = obj?.optString("availableStorage", "")
                    if (!avail.isNullOrBlank()) {
                        Result.success(avail)
                    } else {
                        Result.success("Available")
                    }
                } else {
                    Result.success("Available")
                }
            }
        } catch (_: Exception) {
            Result.success("Available")
        }
    }

    /**
     * Fetch list of all filenames already uploaded for a session.
     * GET /api/staging/:sessionId
     */
    suspend fun fetchSessionUploadedFiles(sessionId: String): Set<String> = withContext(Dispatchers.IO) {
        try {
            val endpoint = cleanUrl("api/staging/${sessionId.trim().trimStart('/')}")
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("x-api-key", apiKey)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptySet()
                val bodyStr = response.body?.string() ?: return@withContext emptySet()
                val obj = JSONObject(bodyStr)
                val photosArr = obj.optJSONArray("photos") ?: obj.optJSONArray("files") ?: return@withContext emptySet()
                val set = mutableSetOf<String>()
                for (i in 0 until photosArr.length()) {
                    val f = photosArr.getJSONObject(i)
                    val name = f.optString("file_name").ifBlank { f.optString("fileName") }
                    if (name.isNotBlank()) set.add(name)
                }
                set
            }
        } catch (e: Exception) {
            Log.w("ApiClient", "Error fetching session uploaded files: ${e.message}")
            emptySet()
        }
    }
}

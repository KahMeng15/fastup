package com.fastup.upload

import android.util.Log
import com.fastup.model.*
import com.fastup.util.AppLogger
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
    val wifiOnly: Boolean,
    val scopes: List<String> = emptyList()
)

data class StagingFoldersResult(
    val folders: List<String>,
    val scopes: List<String> = emptyList()
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

    @Volatile
    var networkSimulation: com.fastup.model.NetworkSimulationConfig = com.fastup.model.NetworkSimulationConfig()

    private val client = OkHttpClient.Builder()
        .connectionPool(okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))
        .addInterceptor { chain ->
            val sim = networkSimulation
            val original = chain.request()
            val isUploadRequest = original.url.encodedPath.contains("/api/staging/upload")

            if (sim.enabled) {
                // 1. Artificial latency delay (apply to uploads or non-frequent calls)
                if (sim.latencyMs > 0) {
                    try {
                        Thread.sleep(sim.latencyMs)
                    } catch (_: InterruptedException) {}
                }
                // 2. Initial connection drop simulation (pre-handshake packet loss)
                // Only drop on uploads or lightly on other queries, avoid completely freezing health probes
                if (isUploadRequest && sim.dropRatePercent > 0 && kotlin.random.Random.nextInt(100) < (sim.dropRatePercent / 2).coerceAtLeast(1)) {
                    throw IOException("Simulated network drop: Packet loss before connection established (${sim.dropRatePercent}% rate)")
                }
            }

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
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Dedicated lightweight probe client with short 3s timeouts and no retries for rapid failure detection
    private val pingClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
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
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit,
        isFavorite: Boolean = false
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
            val sim = networkSimulation
            val progressBody = ProgressRequestBody(
                delegate = fileRequestBody,
                throttleKbps = if (sim.enabled) sim.throttleKbps else 0L,
                dropRatePercent = if (sim.enabled) sim.dropRatePercent else 0,
                onProgress = onProgress
            )

            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("sessionId", validSessionId)
                .addFormDataPart("session_id", validSessionId)
                .addFormDataPart("folderPath", validSessionId)

            if (isFavorite) {
                multipartBuilder.addFormDataPart("is_favorite", "true")
                multipartBuilder.addFormDataPart("isFavorite", "true")
            }

            val multipartBody = multipartBuilder
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
            AppLogger.i("Upload", "Uploading ${file.name} to session '$validSessionId' (${file.length()} bytes)")
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val err = response.body?.string() ?: "HTTP ${response.code}"
                        AppLogger.e("Upload", "Upload failed for ${file.name}: HTTP ${response.code} - $err")
                        Result.failure(IOException("Upload failed (${response.code}): $err"))
                    } else {
                        val respStr = response.body?.string() ?: ""
                        AppLogger.i("Upload", "Upload complete for ${file.name} (HTTP ${response.code})")
                        Result.success(respStr)
                    }
                }
            } finally {
                activeUploadCall = null
            }
        } catch (e: Exception) {
            AppLogger.e("Upload", "Error uploading photo ${file.name}", e)
            Log.e("ApiClient", "Error uploading photo ${file.name}", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch list of existing staging folders and allowed scopes.
     * GET /api/staging/folders
     */
    suspend fun fetchStagingFoldersResult(): Result<StagingFoldersResult> = withContext(Dispatchers.IO) {
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
                    val scopes = mutableListOf<String>()
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

                    val scopesArr = obj?.optJSONArray("scopes")
                    if (scopesArr != null) {
                        for (i in 0 until scopesArr.length()) {
                            val sc = scopesArr.optString(i)?.trim()?.trim('/')
                            if (!sc.isNullOrEmpty()) scopes.add(sc)
                        }
                    }

                    Result.success(StagingFoldersResult(folders = folders, scopes = scopes))
                }
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Error fetching staging folders", e)
            Result.failure(e)
        }
    }

    suspend fun fetchStagingFolders(): Result<List<String>> =
        fetchStagingFoldersResult().map { it.folders }

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
                val cleanSession = sessionId.trim().trimStart('/')
                val json = JSONObject().apply {
                    put("sessionId", cleanSession)
                    put("session_id", cleanSession)
                    put("expires_in_hours", expiresInHours)
                }

                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url(cleanUrl("api/staging/create-share"))
                    .addHeader("x-api-key", apiKey)
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: "{}"
                    if (!response.isSuccessful) {
                        val errMsg = try { JSONObject(bodyStr).optString("error", "HTTP ${response.code}") } catch (_: Exception) { "HTTP ${response.code}" }
                        Result.failure(IOException("Server error creating share link: $errMsg"))
                    } else {
                        val obj = JSONObject(bodyStr)
                        // The server MUST generate the shareToken in shared_folders table.
                        val serverToken = when {
                            obj.has("shareToken") -> obj.getString("shareToken")
                            obj.has("share_token") -> obj.getString("share_token")
                            else -> null
                        }
                        if (serverToken.isNullOrBlank()) {
                            return@use Result.failure(IOException("Server did not return a valid share token"))
                        }

                        val rawUrl = when {
                            obj.has("shareUrl") -> obj.getString("shareUrl")
                            obj.has("share_url") -> obj.getString("share_url")
                            obj.has("url") -> obj.getString("url")
                            else -> "${baseUrl.trimEnd('/')}/share/$serverToken"
                        }

                        // Ensure link uses phone-accessible server host if backend configured with localhost
                        val shareUrl = if (rawUrl.startsWith("/")) {
                            "${baseUrl.trimEnd('/')}$rawUrl"
                        } else if (rawUrl.contains("localhost") || rawUrl.contains("127.0.0.1")) {
                            val baseUri = try { java.net.URI(baseUrl) } catch (e: Exception) { null }
                            if (baseUri != null && baseUri.host != null) {
                                rawUrl.replace("localhost", baseUri.host).replace("127.0.0.1", baseUri.host)
                            } else rawUrl
                        } else {
                            rawUrl
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

                    val scopesList = mutableListOf<String>()
                    val scopesArr = jsonObj.optJSONArray("scopes")
                    if (scopesArr != null) {
                        for (i in 0 until scopesArr.length()) {
                            val sc = scopesArr.optString(i)?.trim()?.trim('/')
                            if (!sc.isNullOrEmpty()) scopesList.add(sc)
                        }
                    }
                    if (scopesList.isEmpty() && folderPath.isNotBlank()) {
                        scopesList.add(folderPath.trim().trim('/'))
                    }

                    Result.success(
                        ClaimOtpResult(
                            token = token,
                            photographerName = photographerName,
                            folderPath = folderPath,
                            serverUrl = sUrl,
                            uploadFormat = sFormat,
                            autoUpload = sAuto,
                            wifiOnly = sWifi,
                            scopes = scopesList
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
     * Light-weight ping probe to check if the target server host is reachable on the network.
     * Uses dedicated fast pingClient with 3-second timeout so it never blocks or hangs the app.
     * Distinguishes between completely unreachable server and frontend-up/backend-down (HTTP 502/503/504).
     */
    suspend fun pingServer(targetUrl: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val base = (targetUrl ?: baseUrl).trim().trimEnd('/')
            val endpoint = "$base/api/staging/ping"
            val request = Request.Builder()
                .url(endpoint)
                .get()
                .build()

            pingClient.newCall(request).execute().use { response ->
                if (response.code in 502..504) {
                    AppLogger.w("Network", "Ping $endpoint: Backend container down (HTTP ${response.code})")
                    Result.failure(IOException("BACKEND_DOWN: HTTP ${response.code} (Backend container is down or starting)"))
                } else if (response.isSuccessful || response.code in 200..499) {
                    AppLogger.d("Network", "Ping $endpoint OK (HTTP ${response.code})")
                    Result.success(true)
                } else {
                    AppLogger.w("Network", "Ping $endpoint HTTP ${response.code}")
                    Result.failure(IOException("Server HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            // Rapid fallback attempt to /api/staging/folders with same fast pingClient
            try {
                val fallbackBase = (targetUrl ?: baseUrl).trim().trimEnd('/')
                val fallbackReq = Request.Builder()
                    .url("$fallbackBase/api/staging/folders")
                    .get()
                    .build()
                pingClient.newCall(fallbackReq).execute().use { fbResponse ->
                    if (fbResponse.code in 502..504) {
                        return@withContext Result.failure(IOException("BACKEND_DOWN: HTTP ${fbResponse.code} (Backend container is down)"))
                    } else if (fbResponse.code in 200..499) {
                        return@withContext Result.success(true)
                    }
                }
            } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    /**
     * Check if the server is reachable and responding.
     * Distinguishes between network offline and expired authentication credentials.
     */
    suspend fun checkConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // 1. Probe network reachability first
            val ping = pingServer()
            if (ping.isFailure) {
                return@withContext ping
            }

            // 2. Validate current apiKey credentials
            val request = Request.Builder()
                .url(cleanUrl("api/staging/sessions"))
                .addHeader("x-api-key", apiKey)
                .get()
                .build()

            pingClient.newCall(request).execute().use { response ->
                if (response.code in 502..504) {
                    AppLogger.w("Network", "Server check: Backend container is down (HTTP ${response.code})")
                    Result.failure(IOException("BACKEND_DOWN: HTTP ${response.code} (Backend container is down or starting)"))
                } else if (response.isSuccessful) {
                    AppLogger.i("Network", "Connection verified to $baseUrl (HTTP ${response.code})")
                    Result.success(true)
                } else if (response.code == 401 || response.code == 403) {
                    AppLogger.w("Network", "Server check: Auth expired / invalid credentials (HTTP ${response.code})")
                    Result.failure(IOException("AUTH_EXPIRED: HTTP ${response.code}"))
                } else {
                    AppLogger.w("Network", "Server check returned HTTP ${response.code}")
                    Result.failure(IOException("Server HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            AppLogger.e("Network", "Connection check failed: ${e.message}", e)
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
                    } else if (obj != null && obj.has("availableBytes") && !obj.isNull("availableBytes")) {
                        val bytes = obj.optLong("availableBytes", 0L)
                        val formatted = when {
                            bytes >= 1024L * 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.0f TB", bytes.toDouble() / (1024L * 1024L * 1024L * 1024L))
                            bytes >= 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.0f GB", bytes.toDouble() / (1024L * 1024L * 1024L))
                            bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.0f MB", bytes.toDouble() / (1024L * 1024L))
                            else -> "$bytes B"
                        }
                        Result.success(formatted)
                    } else {
                        Result.success("--")
                    }
                } else {
                    Result.failure(IOException("Server HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch list of all filenames already uploaded for a session.
     * GET /api/staging/:sessionId
     */
    suspend fun fetchSessionUploadedFiles(sessionId: String): Set<String> = withContext(Dispatchers.IO) {
        val serverPhotos = fetchSessionPhotos(sessionId, view = "all")
        serverPhotos.map { it.fileName }.filter { it.isNotBlank() }.toSet()
    }

    /**
     * Fetch photos for a staging session with curation info (burst_group_id, is_hero, burst_count, quality_score).
     * GET /api/staging/:sessionId?view=curated or ?view=all
     */
    suspend fun fetchSessionPhotos(
        sessionId: String,
        view: String = "all"
    ): List<com.fastup.model.ServerStagingPhoto> = withContext(Dispatchers.IO) {
        try {
            val cleanSession = sessionId.trim().trimStart('/')
            val endpoint = cleanUrl("api/staging/$cleanSession?view=$view")
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("x-api-key", apiKey)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val obj = JSONObject(bodyStr)
                val photosArr = obj.optJSONArray("photos") ?: obj.optJSONArray("files") ?: return@withContext emptyList()
                val resultList = mutableListOf<com.fastup.model.ServerStagingPhoto>()
                for (i in 0 until photosArr.length()) {
                    val f = photosArr.getJSONObject(i)
                    val id = f.optString("id")
                    val name = f.optString("file_name").ifBlank { f.optString("fileName") }
                    val folderPath = f.optString("folder_path")
                    val burstGroupId = if (f.isNull("burst_group_id")) null else f.optString("burst_group_id").ifBlank { null }
                    val isHero = if (f.isNull("is_hero")) true else f.optBoolean("is_hero", true)
                    val burstCount = f.optInt("burst_count", 1)
                    val qualityScore = if (f.isNull("quality_score")) null else f.optDouble("quality_score").toFloat()
                    val faceCount = if (f.isNull("face_count")) null else f.optInt("face_count")
                    val isFavorite = f.optBoolean("is_favorite", false) || f.optBoolean("isFavorite", false)

                    if (name.isNotBlank()) {
                        resultList.add(
                            com.fastup.model.ServerStagingPhoto(
                                id = id,
                                fileName = name,
                                folderPath = folderPath,
                                burstGroupId = burstGroupId,
                                isHero = isHero,
                                burstCount = burstCount,
                                qualityScore = qualityScore,
                                faceCount = faceCount,
                                isFavorite = isFavorite
                            )
                        )
                    }
                }
                resultList
            }
        } catch (e: Exception) {
            Log.w("ApiClient", "Error fetching session photos: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch burst sequence alternates for a given media ID.
     * GET /api/staging/media/:id/alternates
     */
    suspend fun fetchAlternates(mediaId: String): List<com.fastup.model.ServerStagingPhoto> = withContext(Dispatchers.IO) {
        try {
            val endpoint = cleanUrl("api/staging/media/$mediaId/alternates")
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("x-api-key", apiKey)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val obj = JSONObject(bodyStr)
                val alternatesArr = obj.optJSONArray("alternates") ?: return@withContext emptyList()
                val resultList = mutableListOf<com.fastup.model.ServerStagingPhoto>()
                for (i in 0 until alternatesArr.length()) {
                    val f = alternatesArr.getJSONObject(i)
                    val id = f.optString("id")
                    val name = f.optString("file_name").ifBlank { f.optString("fileName") }
                    val folderPath = f.optString("folder_path")
                    val burstGroupId = if (f.isNull("burst_group_id")) null else f.optString("burst_group_id").ifBlank { null }
                    val isHero = if (f.isNull("is_hero")) true else f.optBoolean("is_hero", true)
                    val burstCount = f.optInt("burst_count", 1)
                    val qualityScore = if (f.isNull("quality_score")) null else f.optDouble("quality_score").toFloat()
                    val faceCount = if (f.isNull("face_count")) null else f.optInt("face_count")
                    val isFavorite = f.optBoolean("is_favorite", false) || f.optBoolean("isFavorite", false)

                    if (name.isNotBlank()) {
                        resultList.add(
                            com.fastup.model.ServerStagingPhoto(
                                id = id,
                                fileName = name,
                                folderPath = folderPath,
                                burstGroupId = burstGroupId,
                                isHero = isHero,
                                burstCount = burstCount,
                                qualityScore = qualityScore,
                                faceCount = faceCount,
                                isFavorite = isFavorite
                            )
                        )
                    }
                }
                resultList
            }
        } catch (e: Exception) {
            Log.w("ApiClient", "Error fetching alternates: ${e.message}")
            emptyList()
        }
    }

    /**
     * Set a photo as the primary Hero of its burst group.
     * POST /api/staging/media/:id/set-hero
     */
    suspend fun setHeroPhoto(mediaId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val endpoint = cleanUrl("api/staging/media/$mediaId/set-hero")
            val emptyBody = okhttp3.RequestBody.create(null, ByteArray(0))
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("x-api-key", apiKey)
                .post(emptyBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(IOException("Server HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Toggle visibility (curated vs hidden) for an alternate photo.
     * POST /api/staging/media/:id/toggle-visibility
     */
    suspend fun togglePhotoVisibility(mediaId: String, show: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val endpoint = cleanUrl("api/staging/media/$mediaId/toggle-visibility")
            val jsonBody = JSONObject().apply { put("show", show) }
            val reqBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("x-api-key", apiKey)
                .post(reqBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(IOException("Server HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Toggle or set favorite status for a staged photo.
     * POST /api/staging/media/:id/favorite
     */
    suspend fun toggleFavorite(mediaId: String, isFavorite: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val endpoint = cleanUrl("api/staging/media/$mediaId/favorite")
            val json = JSONObject().apply {
                put("is_favorite", isFavorite)
            }
            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("x-api-key", apiKey)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: "{}"
                    val obj = JSONObject(bodyStr)
                    val newFav = obj.optBoolean("is_favorite", isFavorite)
                    Result.success(newFav)
                } else {
                    Result.failure(IOException("Server HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check if a folder has active Google Drive sync.
     * GET /api/gdrive/sync?folderPath=:folderPath&sourceType=FASTUP
     */
    suspend fun fetchFolderGDriveSync(
        folderPath: String,
        sourceType: String = "FASTUP"
    ): Result<FolderSyncInfo> = withContext(Dispatchers.IO) {
        try {
            val encodedFolder = java.net.URLEncoder.encode(folderPath.trim().trimStart('/'), "UTF-8")
            val endpoint = cleanUrl("api/gdrive/sync?folderPath=$encodedFolder&sourceType=$sourceType")
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("x-api-key", apiKey)
                .addHeader("x-photographer-token", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Result.failure(IOException("HTTP ${response.code}"))
                } else {
                    val bodyStr = response.body?.string() ?: "{}"
                    val obj = JSONObject(bodyStr)
                    val synced = obj.optBoolean("synced", false)
                    if (!synced || !obj.has("sync") || obj.isNull("sync")) {
                        Result.success(FolderSyncInfo(synced = false))
                    } else {
                        val s = obj.getJSONObject("sync")
                        val syncRecord = FolderGDriveSync(
                            id = s.optString("id"),
                            accountId = s.optString("account_id"),
                            folderPath = s.optString("folder_path"),
                            gdriveFolderId = s.optString("gdrive_folder_id"),
                            gdriveFolderName = s.optString("gdrive_folder_name"),
                            gdriveLink = s.optString("gdrive_link"),
                            syncedFileCount = s.optInt("syncedFileCount", 0),
                            totalLocalJpegs = s.optInt("totalLocalJpegs", 0),
                            syncStatus = s.optString("sync_status", "IDLE"),
                            isActive = s.optBoolean("is_active", true)
                        )
                        Result.success(FolderSyncInfo(synced = true, sync = syncRecord))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Error fetching GDrive sync info: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Fetch connected Google Accounts for Google Drive sync.
     * GET /api/gdrive/accounts
     */
    suspend fun fetchGoogleAccounts(): Result<List<GoogleAccount>> = withContext(Dispatchers.IO) {
        try {
            val endpoint = cleanUrl("api/gdrive/accounts")
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("x-api-key", apiKey)
                .addHeader("x-photographer-token", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Result.failure(IOException("Failed to list Google Accounts: HTTP ${response.code}"))
                } else {
                    val bodyStr = response.body?.string() ?: "{}"
                    val obj = JSONObject(bodyStr)
                    val arr = obj.optJSONArray("accounts") ?: org.json.JSONArray()
                    val accounts = mutableListOf<GoogleAccount>()
                    for (i in 0 until arr.length()) {
                        val a = arr.getJSONObject(i)
                        val id = a.optString("id")
                        val email = a.optString("email")
                        val label = if (a.isNull("label")) null else a.optString("label").ifBlank { null }
                        if (id.isNotBlank() && email.isNotBlank()) {
                            accounts.add(GoogleAccount(id = id, email = email, label = label))
                        }
                    }
                    Result.success(accounts)
                }
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Error listing Google accounts: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * List remote folders in a Google Drive account.
     * GET /api/gdrive/folders?accountId=:accountId&parentId=:parentId
     */
    suspend fun fetchGoogleDriveFolders(
        accountId: String,
        parentId: String? = null
    ): Result<List<GDriveFolder>> = withContext(Dispatchers.IO) {
        try {
            val encodedAccount = java.net.URLEncoder.encode(accountId, "UTF-8")
            val parentQuery = if (!parentId.isNullOrBlank()) {
                "&parentId=" + java.net.URLEncoder.encode(parentId, "UTF-8")
            } else ""
            val endpoint = cleanUrl("api/gdrive/folders?accountId=$encodedAccount$parentQuery")
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("x-api-key", apiKey)
                .addHeader("x-photographer-token", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Result.failure(IOException("Failed to list Drive folders: HTTP ${response.code}"))
                } else {
                    val bodyStr = response.body?.string() ?: "{}"
                    val obj = JSONObject(bodyStr)
                    val arr = obj.optJSONArray("folders") ?: org.json.JSONArray()
                    val list = mutableListOf<GDriveFolder>()
                    for (i in 0 until arr.length()) {
                        val f = arr.getJSONObject(i)
                        val id = f.optString("id")
                        val name = f.optString("name")
                        if (id.isNotBlank() && name.isNotBlank()) {
                            list.add(GDriveFolder(id = id, name = name))
                        }
                    }
                    Result.success(list)
                }
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Error fetching Google Drive folders: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Initialize or set up Google Drive sync for a folder.
     * POST /api/gdrive/sync
     */
    suspend fun initializeGDriveSync(
        accountId: String,
        folderPath: String,
        sourceType: String = "FASTUP",
        folderName: String? = null,
        existingFolderId: String? = null
    ): Result<FolderGDriveSync> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("accountId", accountId)
                put("folderPath", folderPath.trim().trimStart('/'))
                put("sourceType", sourceType)
                if (!folderName.isNullOrBlank()) put("folderName", folderName)
                if (!existingFolderId.isNullOrBlank()) put("existingFolderId", existingFolderId)
            }
            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val endpoint = cleanUrl("api/gdrive/sync")
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("x-api-key", apiKey)
                .addHeader("x-photographer-token", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: "{}"
                if (!response.isSuccessful) {
                    val err = try { JSONObject(bodyStr).optString("error", bodyStr) } catch (_: Exception) { bodyStr }
                    Result.failure(IOException(err.ifBlank { "Setup failed (${response.code})" }))
                } else {
                    val obj = JSONObject(bodyStr)
                    val s = obj.optJSONObject("sync") ?: obj
                    val syncRecord = FolderGDriveSync(
                        id = s.optString("id"),
                        accountId = s.optString("account_id", accountId),
                        folderPath = s.optString("folder_path", folderPath),
                        gdriveFolderId = s.optString("gdrive_folder_id"),
                        gdriveFolderName = s.optString("gdrive_folder_name", folderName ?: ""),
                        gdriveLink = s.optString("gdrive_link"),
                        syncedFileCount = s.optInt("syncedFileCount", 0),
                        totalLocalJpegs = s.optInt("totalLocalJpegs", 0),
                        syncStatus = s.optString("sync_status", "IDLE"),
                        isActive = s.optBoolean("is_active", true)
                    )
                    Result.success(syncRecord)
                }
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Error initializing GDrive sync: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Get Google Drive link for an individual media file.
     * GET /api/gdrive/media/:mediaId/link
     */
    suspend fun fetchMediaGDriveLink(mediaId: String): Result<MediaGDriveLinkResult> = withContext(Dispatchers.IO) {
        try {
            val endpoint = cleanUrl("api/gdrive/media/$mediaId/link")
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("x-api-key", apiKey)
                .addHeader("x-photographer-token", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Result.failure(IOException("HTTP ${response.code}"))
                } else {
                    val bodyStr = response.body?.string() ?: "{}"
                    val obj = JSONObject(bodyStr)
                    val synced = obj.optBoolean("synced", false)
                    val link = if (obj.isNull("link")) null else obj.optString("link").ifBlank { null }
                    val gdriveFileId = if (obj.isNull("gdriveFileId")) null else obj.optString("gdriveFileId").ifBlank { null }
                    Result.success(MediaGDriveLinkResult(synced = synced, link = link, gdriveFileId = gdriveFileId))
                }
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Error fetching media GDrive link: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Create or fetch individual photo share link.
     * POST /api/staging/create-share with { sessionId, mediaId }
     */
    suspend fun createMediaShareLink(
        sessionId: String,
        mediaId: String,
        expiresInHours: Int = 168
    ): Result<ShareSessionInfo> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("sessionId", sessionId.trim().trimStart('/'))
                put("session_id", sessionId.trim().trimStart('/'))
                put("mediaId", mediaId)
                put("expires_in_hours", expiresInHours)
            }

            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(cleanUrl("api/staging/create-share"))
                .addHeader("x-api-key", apiKey)
                .addHeader("x-photographer-token", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: "{}"
                if (!response.isSuccessful) {
                    val err = try { JSONObject(bodyStr).optString("error", bodyStr) } catch (_: Exception) { bodyStr }
                    Result.failure(IOException("Server error creating media share: $err"))
                } else {
                    val obj = JSONObject(bodyStr)
                    val serverToken = when {
                        obj.has("shareToken") -> obj.getString("shareToken")
                        obj.has("share_token") -> obj.getString("share_token")
                        else -> null
                    }
                    if (serverToken.isNullOrBlank()) {
                        return@use Result.failure(IOException("Server did not return a valid share token"))
                    }

                    val rawUrl = when {
                        obj.has("shareUrl") -> obj.getString("shareUrl")
                        obj.has("share_url") -> obj.getString("share_url")
                        obj.has("url") -> obj.getString("url")
                        else -> "${baseUrl.trimEnd('/')}/share/$serverToken"
                    }
                    val shareUrl = if (rawUrl.startsWith("/")) {
                        "${baseUrl.trimEnd('/')}$rawUrl"
                    } else if (rawUrl.contains("localhost") || rawUrl.contains("127.0.0.1")) {
                        val baseUri = try { java.net.URI(baseUrl) } catch (e: Exception) { null }
                        if (baseUri != null && baseUri.host != null) {
                            rawUrl.replace("localhost", baseUri.host).replace("127.0.0.1", baseUri.host)
                        } else rawUrl
                    } else {
                        rawUrl
                    }
                    Result.success(ShareSessionInfo(sessionId = sessionId, shareUrl = shareUrl))
                }
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Error creating media share link: ${e.message}")
            Result.failure(e)
        }
    }
}

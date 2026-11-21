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

class ApiClient(
    var baseUrl: String = "http://10.0.0.43:8000",
    var apiKey: String = "fastup_secret_key"
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

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
            val mediaType = if (file.name.endsWith(".arw", ignoreCase = true)) {
                "application/octet-stream".toMediaTypeOrNull()
            } else {
                "image/jpeg".toMediaTypeOrNull()
            }

            val fileRequestBody = file.asRequestBody(mediaType)
            val progressBody = ProgressRequestBody(fileRequestBody, onProgress)

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("session_id", sessionId)
                .addFormDataPart("folderPath", sessionId)
                .addFormDataPart(
                    "file",
                    file.name,
                    progressBody
                )
                .build()

            val request = Request.Builder()
                .url(cleanUrl("api/staging/upload"))
                .addHeader("x-api-key", apiKey)
                .post(multipartBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string() ?: "HTTP ${response.code}"
                    Result.failure(IOException("Upload failed (${response.code}): $err"))
                } else {
                    val respStr = response.body?.string() ?: ""
                    Result.success(respStr)
                }
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
            val request = Request.Builder()
                .url(cleanUrl("api/staging/folders"))
                .addHeader("x-api-key", apiKey)
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
}

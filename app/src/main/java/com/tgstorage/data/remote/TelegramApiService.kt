package com.tgstorage.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Low-level Telegram Bot API client using OkHttp.
 * Reference: https://core.telegram.org/bots/api
 */
class TelegramApiService {

    companion object {
        private const val BASE_URL = "https://api.telegram.org/bot"
        private const val FILE_URL = "https://api.telegram.org/file/bot"

        /** Shared OkHttpClient — reuses connections, thread pools, TLS sessions */
        val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /** Download-specific client with longer read timeout for large chunks */
        val downloadClient: OkHttpClient = sharedClient.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private val client: OkHttpClient get() = sharedClient

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * GET /getMe — validates the token and returns bot info.
     */
    suspend fun getMe(token: String): Result<TelegramUser> = executeGet(
        url = "${BASE_URL}${token}/getMe",
        deserialize = { body ->
            val resp = json.decodeFromString<TelegramResponse<TelegramUser>>(body)
            if (resp.ok && resp.result != null) resp.result
            else throw TelegramApiException(resp.description ?: "getMe failed", resp.errorCode)
        },
    )

    /**
     * POST /sendMessage — sends a text message to verify channel access.
     */
    suspend fun sendMessage(
        token: String,
        chatId: String,
        text: String,
    ): Result<TelegramMessage> {
        val url = "${BASE_URL}${token}/sendMessage?chat_id=${chatId}&text=${
            java.net.URLEncoder.encode(text, "UTF-8")
        }"
        return executeGet(
            url = url,
            deserialize = { body ->
                val resp = json.decodeFromString<TelegramResponse<TelegramMessage>>(body)
                if (resp.ok && resp.result != null) resp.result
                else throw TelegramApiException(resp.description ?: "sendMessage failed", resp.errorCode)
            },
        )
    }

    /**
     * POST /deleteMessage — deletes the verification message.
     */
    suspend fun deleteMessage(
        token: String,
        chatId: String,
        messageId: Long,
    ): Result<Boolean> {
        val url = "${BASE_URL}${token}/deleteMessage?chat_id=${chatId}&message_id=${messageId}"
        return executeGet(
            url = url,
            deserialize = { body ->
                val resp = json.decodeFromString<TelegramResponse<Boolean>>(body)
                if (resp.ok) true
                else throw TelegramApiException(resp.description ?: "deleteMessage failed", resp.errorCode)
            },
        )
    }

    /**
     * POST /sendDocument — uploads a file to the channel.
     */
    suspend fun sendDocument(
        token: String,
        chatId: String,
        file: File,
        fileName: String = file.name,
        caption: String? = null,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null,
    ): Result<TelegramMessage> = withContext(Dispatchers.IO) {
        runCatching {
            val fileBody: RequestBody = if (onProgress != null) {
                CountingRequestBody(
                    delegate = file.asRequestBody("application/octet-stream".toMediaType()),
                    onProgress = onProgress,
                )
            } else {
                file.asRequestBody("application/octet-stream".toMediaType())
            }

            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("document", fileName, fileBody)

            if (caption != null) {
                builder.addFormDataPart("caption", caption)
            }

            val requestBody = builder.build()

            val request = Request.Builder()
                .url("${BASE_URL}${token}/sendDocument")
                .post(requestBody)
                .build()

            val responseBody = client.newCall(request).await()
            val resp = json.decodeFromString<TelegramResponse<TelegramMessage>>(responseBody)
            if (resp.ok && resp.result != null) resp.result
            else throw TelegramApiException(resp.description ?: "sendDocument failed", resp.errorCode)
        }
    }

    /**
     * GET /getFile — gets file download path.
     */
    suspend fun getFile(
        token: String,
        fileId: String,
    ): Result<TelegramFile> = executeGet(
        url = "${BASE_URL}${token}/getFile?file_id=${fileId}",
        deserialize = { body ->
            val resp = json.decodeFromString<TelegramResponse<TelegramFile>>(body)
            if (resp.ok && resp.result != null) resp.result
            else throw TelegramApiException(resp.description ?: "getFile failed", resp.errorCode)
        },
    )

    /**
     * POST /forwardMessage — forwards a message to another (or same) chat.
     * Returns the new Message with fresh document.file_id.
     * Used to recover stale file_ids: forward the chunk's message to the same channel,
     * extract the new file_id, then delete the forwarded copy.
     */
    suspend fun forwardMessage(
        token: String,
        chatId: String,
        fromChatId: String,
        messageId: Long,
    ): Result<TelegramMessage> = executeGet(
        url = "${BASE_URL}${token}/forwardMessage?chat_id=${chatId}&from_chat_id=${fromChatId}&message_id=${messageId}",
        deserialize = { body ->
            val resp = json.decodeFromString<TelegramResponse<TelegramMessage>>(body)
            if (resp.ok && resp.result != null) resp.result
            else throw TelegramApiException(resp.description ?: "forwardMessage failed", resp.errorCode)
        },
    )

    /**
     * POST /copyMessage — copies a message to another (or same) chat.
     * Returns a MessageId object (not a full Message). The copied message
     * is a NEW message with fresh document.file_id.
     * Used as a fallback when forwardMessage fails.
     */
    suspend fun copyMessage(
        token: String,
        chatId: String,
        fromChatId: String,
        messageId: Long,
    ): Result<TelegramMessage> = executeGet(
        url = "${BASE_URL}${token}/copyMessage?chat_id=${chatId}&from_chat_id=${fromChatId}&message_id=${messageId}",
        deserialize = { body ->
            // copyMessage returns {"ok":true,"result":{"message_id":123}}
            // We need the full message to get the document, so fetch it back
            // Actually copyMessage only returns message_id, not the document
            // So we parse the message_id and use it to note the copy exists
            val resp = json.decodeFromString<TelegramResponse<TelegramMessage>>(body)
            if (resp.ok && resp.result != null) resp.result
            else throw TelegramApiException(resp.description ?: "copyMessage failed", resp.errorCode)
        },
    )

    /**
     * Download file bytes by file_path.
     */
    suspend fun downloadFile(
        token: String,
        filePath: String,
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("${FILE_URL}${token}/${filePath}")
                .build()
            val response = downloadClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw TelegramApiException("Download failed: ${response.code}", response.code)
            }
            response.body?.bytes() ?: throw TelegramApiException("Empty response body", null)
        }
    }

    /**
     * Download file by streaming directly to an OutputStream.
     * Much faster than loading entire file into memory as ByteArray.
     * Uses dedicated download client with longer read timeout.
     * Supports progress callbacks for UI progress tracking.
     */
    suspend fun downloadFileStreaming(
        token: String,
        filePath: String,
        outputStream: java.io.OutputStream,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("${FILE_URL}${token}/${filePath}")
                .build()
            val response = downloadClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw TelegramApiException("Download failed: ${response.code}", response.code)
            }
            val body = response.body ?: throw TelegramApiException("Empty response body", null)
            val totalBytes = body.contentLength()
            var bytesRead = 0L
            val buffer = ByteArray(64 * 1024) // 64KB buffer for fast streaming
            body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    outputStream.write(buffer, 0, read)
                    bytesRead += read
                    onProgress?.invoke(bytesRead, totalBytes)
                }
            }
            outputStream.flush()
            bytesRead
        }
    }

    // ─── File path cache ─────────────────────────────
    // Telegram says file_path links are valid for at least 1 hour.
    // Cache them to avoid repeated getFile API calls.
    private data class CachedFilePath(val path: String, val expiresAt: Long)
    private val filePathCache = java.util.concurrent.ConcurrentHashMap<String, CachedFilePath>()
    private val filePathCacheTtl = 50L * 60 * 1000 // 50 minutes (safe margin under 1hr)

    /**
     * Get file_path with caching. Avoids redundant getFile API calls.
     */
    suspend fun getFilePathCached(
        token: String,
        fileId: String,
    ): Result<String> {
        val cached = filePathCache[fileId]
        if (cached != null && System.currentTimeMillis() < cached.expiresAt) {
            return Result.success(cached.path)
        }
        return getFile(token, fileId).map { tgFile ->
            val path = tgFile.filePath
                ?: throw TelegramApiException("No file_path returned", null)
            filePathCache[fileId] = CachedFilePath(path, System.currentTimeMillis() + filePathCacheTtl)
            path
        }
    }

    /**
     * GET /getChat — returns full chat info including pinned_message.
     * Used to discover backup documents pinned in the channel.
     */
    suspend fun getChat(
        token: String,
        chatId: String,
    ): Result<TelegramChatFullInfo> = executeGet(
        url = "${BASE_URL}${token}/getChat?chat_id=${chatId}",
        deserialize = { body ->
            val resp = json.decodeFromString<TelegramResponse<TelegramChatFullInfo>>(body)
            if (resp.ok && resp.result != null) resp.result
            else throw TelegramApiException(resp.description ?: "getChat failed", resp.errorCode)
        },
    )

    /**
     * POST /pinChatMessage — pins a message in the channel.
     * Used to pin backup documents so they can be discovered via getChat.
     */
    suspend fun pinChatMessage(
        token: String,
        chatId: String,
        messageId: Long,
        disableNotification: Boolean = true,
    ): Result<Boolean> {
        val url = "${BASE_URL}${token}/pinChatMessage?chat_id=${chatId}" +
                "&message_id=${messageId}&disable_notification=${disableNotification}"
        return executeGet(
            url = url,
            deserialize = { body ->
                val resp = json.decodeFromString<TelegramResponse<Boolean>>(body)
                if (resp.ok) true
                else throw TelegramApiException(resp.description ?: "pinChatMessage failed", resp.errorCode)
            },
        )
    }

    /**
     * POST /getUpdates — fetches recent updates using long-polling.
     * Used to auto-detect channels the bot has been added to.
     *
     * @param offset If > 0, only return updates with update_id >= offset.
     *               Pass lastUpdateId + 1 to acknowledge previous updates.
     * @param timeout Long-poll seconds (0 = immediate, default 5).
     *                The server holds the connection until an update arrives or timeout elapses.
     * @param allowedUpdates Which update types to receive. Defaults to channel_post + message + my_chat_member.
     */
    suspend fun getUpdates(
        token: String,
        offset: Long = 0,
        timeout: Int = 5,
        allowedUpdates: List<String> = listOf("channel_post", "message", "my_chat_member"),
    ): Result<List<TelegramUpdate>> = withContext(Dispatchers.IO) {
        runCatching {
            val bodyMap = buildMap<String, kotlinx.serialization.json.JsonElement> {
                put("timeout", kotlinx.serialization.json.JsonPrimitive(timeout))
                put("allowed_updates", kotlinx.serialization.json.JsonArray(
                    allowedUpdates.map { kotlinx.serialization.json.JsonPrimitive(it) }
                ))
                if (offset > 0) {
                    put("offset", kotlinx.serialization.json.JsonPrimitive(offset))
                }
            }
            val jsonBody = json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.JsonObject(bodyMap),
            )
            val requestBody = jsonBody.toRequestBody(
                "application/json; charset=utf-8".toMediaType(),
            )
            // Use a longer OkHttp timeout for long-polling
            val longPollClient = client.newBuilder()
                .readTimeout((timeout + 5).toLong(), java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("${BASE_URL}${token}/getUpdates")
                .post(requestBody)
                .build()
            val responseBody = longPollClient.newCall(request).await()
            val resp = json.decodeFromString<TelegramResponse<List<TelegramUpdate>>>(responseBody)
            if (resp.ok && resp.result != null) resp.result
            else throw TelegramApiException(resp.description ?: "getUpdates failed", resp.errorCode)
        }
    }

    // ─── Helpers ────────────────────────────────────────

    private suspend fun <T> executeGet(
        url: String,
        deserialize: (String) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).build()
            val responseBody = client.newCall(request).await()
            deserialize(responseBody)
        }
    }

    private suspend fun Call.await(): String = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (body != null) {
                    cont.resume(body)
                } else {
                    cont.resumeWithException(IOException("Empty response body"))
                }
            }
        })
    }
}

class TelegramApiException(
    message: String,
    val errorCode: Int? = null,
) : Exception(message)

/**
 * A [RequestBody] wrapper that reports upload progress via [onProgress].
 * Wraps the delegate body and counts bytes written to the sink.
 */
class CountingRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        val countingSink = CountingSink(sink, total, onProgress)
        val bufferedSink = countingSink.buffer()
        delegate.writeTo(bufferedSink)
        bufferedSink.flush()
    }
}

private class CountingSink(
    delegate: Sink,
    private val total: Long,
    private val onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit,
) : ForwardingSink(delegate) {

    private var bytesWritten = 0L

    override fun write(source: Buffer, byteCount: Long) {
        super.write(source, byteCount)
        bytesWritten += byteCount
        onProgress(bytesWritten, total)
    }
}

package com.tgstorage.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    companion object {
        private const val BASE_URL = "https://api.telegram.org/bot"
        private const val FILE_URL = "https://api.telegram.org/file/bot"
    }

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
    ): Result<TelegramMessage> = withContext(Dispatchers.IO) {
        runCatching {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart(
                    "document",
                    fileName,
                    file.asRequestBody("application/octet-stream".toMediaType()),
                )
                .build()

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
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw TelegramApiException("Download failed: ${response.code}", response.code)
            }
            response.body?.bytes() ?: throw TelegramApiException("Empty response body", null)
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

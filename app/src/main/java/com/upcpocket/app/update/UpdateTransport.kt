package com.upcpocket.app.update

import com.upcpocket.app.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class TransportFailure {
    NETWORK,
    TLS,
    HTTP_STATUS,
    TOO_LARGE,
    INVALID_REDIRECT,
    CONTENT_LENGTH,
    IO,
}

/**
 * 传输失败只保留固定原因与数字 HTTP 状态；异常 message / URL / query 不进入用户
 * 可见文本或诊断。调用方据此切源，不把失败显示成“已是最新”。
 */
class UpdateTransportException(
    val failure: TransportFailure,
    val httpStatus: Int? = null,
) : IOException(failure.name)

interface UpdateTransport {
    suspend fun fetch(url: String, source: UpdateSource, maxBytes: Long): ByteArray

    suspend fun download(
        url: String,
        source: UpdateSource,
        expectedBytes: Long,
        destination: File,
        onProgress: (downloadedBytes: Long) -> Unit,
    )
}

class HttpUpdateTransport(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 15_000,
    private val maxRedirects: Int = 4,
) : UpdateTransport {

    override suspend fun fetch(
        url: String,
        source: UpdateSource,
        maxBytes: Long,
    ): ByteArray = withContext(Dispatchers.IO) {
        withConnection(url, source, maxBytes, expectedBytes = null) { connection ->
            val declared = declaredLength(connection)
            if (declared != null && declared > maxBytes) {
                throw UpdateTransportException(TransportFailure.TOO_LARGE)
            }
            val stream = connection.inputStream
            val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024L).toInt())
            val buffer = ByteArray(8 * 1024)
            var total = 0L
            stream.use {
                while (true) {
                    coroutineContext.ensureActive()
                    val read = it.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        throw UpdateTransportException(TransportFailure.TOO_LARGE)
                    }
                    output.write(buffer, 0, read)
                }
            }
            if (declared != null && total != declared) {
                throw UpdateTransportException(TransportFailure.CONTENT_LENGTH)
            }
            output.toByteArray()
        }
    }

    override suspend fun download(
        url: String,
        source: UpdateSource,
        expectedBytes: Long,
        destination: File,
        onProgress: (downloadedBytes: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        destination.delete()
        withConnection(url, source, expectedBytes, expectedBytes = expectedBytes) { connection ->
            val declared = declaredLength(connection)
            if (declared != null && declared != expectedBytes) {
                throw UpdateTransportException(TransportFailure.CONTENT_LENGTH)
            }
            val stream = connection.inputStream
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            FileOutputStream(destination).use { output ->
                stream.use {
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = it.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > expectedBytes) {
                            throw UpdateTransportException(TransportFailure.CONTENT_LENGTH)
                        }
                        output.write(buffer, 0, read)
                        output.flush()
                        onProgress(total)
                    }
                }
            }
            if (total != expectedBytes) {
                throw UpdateTransportException(TransportFailure.CONTENT_LENGTH)
            }
        }
    }

    private suspend fun <T> withConnection(
        rawUrl: String,
        source: UpdateSource,
        maxBytes: Long,
        expectedBytes: Long?,
        block: suspend (HttpURLConnection) -> T,
    ): T {
        var current = parseAndValidate(rawUrl, source)
        var redirects = 0
        while (true) {
            coroutineContext.ensureActive()
            val connection = try {
                current.openConnection() as HttpURLConnection
            } catch (_: Exception) {
                throw UpdateTransportException(TransportFailure.INVALID_REDIRECT)
            }
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", "application/json, application/octet-stream")
            // 明确要求不压缩，长度校验和进度都以实际字节为准。
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "UPC-Pocket-Android/${BuildConfig.VERSION_NAME}")

            try {
                val status = try {
                    connection.responseCode
                } catch (_: SSLException) {
                    throw UpdateTransportException(TransportFailure.TLS)
                } catch (_: CertificateException) {
                    throw UpdateTransportException(TransportFailure.TLS)
                } catch (_: SocketTimeoutException) {
                    throw UpdateTransportException(TransportFailure.NETWORK)
                } catch (_: UnknownHostException) {
                    throw UpdateTransportException(TransportFailure.NETWORK)
                } catch (_: Exception) {
                    throw UpdateTransportException(TransportFailure.NETWORK)
                }

                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrBlank()) {
                        throw UpdateTransportException(TransportFailure.INVALID_REDIRECT)
                    }
                    redirects += 1
                    if (redirects > maxRedirects) {
                        throw UpdateTransportException(TransportFailure.INVALID_REDIRECT)
                    }
                    val next = try {
                        URI(current.toString()).resolve(location).toURL()
                    } catch (_: Exception) {
                        throw UpdateTransportException(TransportFailure.INVALID_REDIRECT)
                    }
                    current = validateTarget(next, source)
                    continue
                }

                if (status != HttpURLConnection.HTTP_OK) {
                    throw UpdateTransportException(TransportFailure.HTTP_STATUS, status)
                }
                val declared = declaredLength(connection)
                if (declared != null && declared > maxBytes) {
                    throw UpdateTransportException(TransportFailure.TOO_LARGE)
                }
                if (expectedBytes != null && declared != null && declared != expectedBytes) {
                    throw UpdateTransportException(TransportFailure.CONTENT_LENGTH)
                }
                return block(connection)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateTransportException) {
                throw e
            } catch (_: SocketTimeoutException) {
                throw UpdateTransportException(TransportFailure.NETWORK)
            } catch (_: SSLException) {
                throw UpdateTransportException(TransportFailure.TLS)
            } catch (_: Exception) {
                throw UpdateTransportException(TransportFailure.IO)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun parseAndValidate(rawUrl: String, source: UpdateSource): URL {
        val url = try {
            URI(rawUrl).toURL()
        } catch (_: Exception) {
            throw UpdateTransportException(TransportFailure.INVALID_REDIRECT)
        }
        return validateTarget(url, source)
    }

    private fun validateTarget(url: URL, source: UpdateSource): URL {
        if (url.protocol != "https" && !(source.cleartextAllowed && url.protocol == "http")) {
            throw UpdateTransportException(TransportFailure.INVALID_REDIRECT)
        }
        if (url.userInfo != null) {
            throw UpdateTransportException(TransportFailure.INVALID_REDIRECT)
        }
        val host = url.host.lowercase()
        if (host !in source.allowedRedirectHosts) {
            throw UpdateTransportException(TransportFailure.INVALID_REDIRECT)
        }
        val allowedPort = URI(source.baseUrl).port
        val effectivePort = if (url.port == -1) {
            if (url.protocol == "https") 443 else 80
        } else {
            url.port
        }
        val sourcePort = if (allowedPort == -1) {
            if (url.protocol == "https") 443 else 80
        } else {
            allowedPort
        }
        // 生产源固定 443；debug 测试源允许其显式端口。
        if (!source.cleartextAllowed && effectivePort != 443) {
            throw UpdateTransportException(TransportFailure.INVALID_REDIRECT)
        }
        if (source.cleartextAllowed && effectivePort != sourcePort) {
            throw UpdateTransportException(TransportFailure.INVALID_REDIRECT)
        }
        return url
    }

    private fun declaredLength(connection: HttpURLConnection): Long? {
        val raw = connection.getHeaderField("Content-Length") ?: return null
        return raw.toLongOrNull()?.takeIf { it >= 0L }
    }
}

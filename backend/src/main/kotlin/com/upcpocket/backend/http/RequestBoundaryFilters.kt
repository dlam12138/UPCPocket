package com.upcpocket.backend.http

import com.fasterxml.jackson.databind.ObjectMapper
import com.upcpocket.backend.service.ApiErrorCode
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = UUID.randomUUID()
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId)
        response.setHeader("X-Request-Id", requestId.toString())
        filterChain.doFilter(request, response)
    }

    companion object {
        const val REQUEST_ID_ATTRIBUTE = "upcpocket.requestId"
    }
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class NoStoreResponseFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val path = request.requestURI
        if (path.startsWith("/api/v1/") || path == "/api/v1" ||
            path.startsWith("/internal/admin/v1/") || path == "/internal/admin/v1"
        ) {
            response.setHeader("Cache-Control", "no-store")
            response.setHeader("Pragma", "no-cache")
        }
        filterChain.doFilter(request, response)
    }
}

private enum class AccessResultCode {
    SUCCESS,
    CLIENT_ERROR,
    SERVER_ERROR,
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class SanitizedAccessLogFilter(private val mapper: ObjectMapper) : OncePerRequestFilter() {
    private val logger = LoggerFactory.getLogger(SanitizedAccessLogFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val started = System.nanoTime()
        try {
            filterChain.doFilter(request, response)
        } catch (_: Exception) {
            if (!response.isCommitted) {
                response.resetBuffer()
                response.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                response.contentType = "application/json"
                response.characterEncoding = Charsets.UTF_8.name()
                val requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) as? UUID ?: UUID.randomUUID()
                mapper.writeValue(response.outputStream, ErrorResponse(ApiErrorCode.INTERNAL_ERROR.name, requestId))
            }
        } finally {
            val requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) as? UUID ?: UUID.randomUUID()
            val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
            val result = when {
                response.status < 400 -> AccessResultCode.SUCCESS
                response.status < 500 -> AccessResultCode.CLIENT_ERROR
                else -> AccessResultCode.SERVER_ERROR
            }
            logger.info("requestId={} result={} elapsedMs={}", requestId, result.name, elapsedMs)
        }
    }
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
class SensitiveCharArrayCleanupFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            filterChain.doFilter(request, response)
        } finally {
            RequestSensitiveCharArrays.clearCurrentRequest()
        }
    }
}

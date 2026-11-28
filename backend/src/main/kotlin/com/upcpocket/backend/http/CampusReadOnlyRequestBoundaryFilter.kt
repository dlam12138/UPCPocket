package com.upcpocket.backend.http

import com.fasterxml.jackson.databind.ObjectMapper
import com.upcpocket.backend.service.ApiErrorCode
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/** These routes accept only their fixed, typed query parameters and never accept request bodies. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 4)
class CampusReadOnlyRequestBoundaryFilter(private val mapper: ObjectMapper) : OncePerRequestFilter() {
    private val queryParameters = mapOf(
        "/api/v1/timetable/index" to emptySet(),
        "/api/v1/timetable/week" to setOf("year", "term", "week"),
        "/api/v1/card/balance" to emptySet(),
        "/api/v1/card/transactions" to setOf("month", "page", "size"),
        "/api/v1/card/transactions/summary" to setOf("month"),
        "/api/v1/grades/terms" to emptySet(),
        "/api/v1/grades/courses" to setOf("year", "term"),
    )

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val path = request.servletPath.ifBlank {
            request.requestURI.removePrefix(request.contextPath)
        }
        val allowed = queryParameters[path]
        if (allowed == null || request.method != "GET") {
            filterChain.doFilter(request, response)
            return
        }
        val hasBody = request.contentLengthLong > 0 || !request.getHeader("Transfer-Encoding").isNullOrBlank()
        val parameters = request.parameterMap
        val invalidParameters = parameters.keys.any { it !in allowed } || parameters.values.any { it.size != 1 }
        if (hasBody || invalidParameters) {
            response.status = ApiErrorCode.VALIDATION_FAILED.httpStatus
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = Charsets.UTF_8.name()
            val requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) as? UUID ?: UUID.randomUUID()
            mapper.writeValue(response.outputStream, ErrorResponse(ApiErrorCode.VALIDATION_FAILED.name, requestId))
            return
        }
        filterChain.doFilter(request, response)
    }
}

package com.upcpocket.backend.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.upcpocket.backend.contract.AccountStore
import com.upcpocket.backend.http.ErrorResponse
import com.upcpocket.backend.http.RequestIdFilter
import com.upcpocket.backend.session.InMemorySessionRegistry
import com.upcpocket.backend.service.ApiErrorCode
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

class PocketTokenAuthenticationFilter(
    private val sessions: InMemorySessionRegistry,
    private val accounts: AccountStore,
    private val mapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method == "POST" &&
            (request.servletPath == "/api/v1/auth/sessions" ||
                request.servletPath == "/api/v1/auth/credential-updates")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            val header = request.getHeader("Authorization")
            if (header != null) {
                if (!header.startsWith("Bearer ") || header.length <= BEARER_PREFIX_LENGTH) {
                    writeError(request, response, ApiErrorCode.TOKEN_INVALID)
                    return
                }
                val identity = sessions.authenticate(header.substring(BEARER_PREFIX_LENGTH))
                    ?: run {
                        writeError(request, response, ApiErrorCode.TOKEN_INVALID)
                        return
                    }
                val account = accounts.findByAccountId(identity.accountId)
                if (account == null || account.disabled || account.credentialGeneration != identity.credentialGeneration) {
                    sessions.revokeCurrent(identity)
                    writeError(request, response, ApiErrorCode.TOKEN_INVALID)
                    return
                }
                if (account.reauthRequired) {
                    writeError(request, response, ApiErrorCode.CREDENTIAL_UPDATE_REQUIRED)
                    return
                }
                if (account.gateRevision != identity.gateRevision) {
                    sessions.revokeCurrent(identity)
                    writeError(request, response, ApiErrorCode.TOKEN_INVALID)
                    return
                }
                val principal = PocketPrincipal(identity)
                val authentication = UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_STUDENT")),
                )
                SecurityContextHolder.getContext().authentication = authentication
            }
        } catch (_: Exception) {
            SecurityContextHolder.clearContext()
            writeError(request, response, ApiErrorCode.DEPENDENCY_UNAVAILABLE)
            return
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        private const val BEARER_PREFIX_LENGTH = 7
    }

    private fun writeError(request: HttpServletRequest, response: HttpServletResponse, code: ApiErrorCode) {
        SecurityContextHolder.clearContext()
        response.status = code.httpStatus
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        val requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) as? UUID ?: UUID.randomUUID()
        mapper.writeValue(response.outputStream, ErrorResponse(code.name, requestId))
    }
}

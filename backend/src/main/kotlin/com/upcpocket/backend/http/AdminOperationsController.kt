package com.upcpocket.backend.http

import com.upcpocket.backend.service.AdminOperationsService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/internal/admin/v1")
class AdminOperationsController(private val adminOperationsService: AdminOperationsService) {
    @PostMapping("/account-lookups")
    fun lookup(
        @RequestBody body: AccountLookupRequest,
        authentication: Authentication,
        request: HttpServletRequest,
    ): AdminLookupResponse {
        try {
            return AdminLookupResponse(
                adminOperationsService.lookupAccount(
                    body.loginName,
                    authentication.name,
                    requestId(request),
                )?.toResponse(),
            )
        } finally {
            body.loginName.fill('\u0000')
            RequestSensitiveCharArrays.clearCurrentRequest()
        }
    }

    @PutMapping("/accounts/{id}/disabled")
    fun setDisabled(
        @PathVariable id: UUID,
        @RequestBody body: DisabledRequest,
        @RequestHeader("If-Match") ifMatch: String,
        authentication: Authentication,
        request: HttpServletRequest,
    ): AdminAccountResponse = adminOperationsService.setDisabled(
        id, body.disabled, parseRevision(ifMatch), authentication.name, requestId(request),
    ).toResponse()

    @DeleteMapping("/accounts/{id}/sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revokeAll(
        @PathVariable id: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        authentication: Authentication,
        request: HttpServletRequest,
    ) {
        adminOperationsService.revokeAll(id, parseRevision(ifMatch), authentication.name, requestId(request))
    }

    @PostMapping("/key-rotations")
    fun rotateKeys(
        @RequestBody body: KeyRotationRequest,
        authentication: Authentication,
        request: HttpServletRequest,
    ): KeyRotationResponse = adminOperationsService.rotateKeys(
        body.targetVersion,
        body.expectedWriteVersion,
        authentication.name,
        requestId(request),
    ).let { KeyRotationResponse(it.writeVersion, it.rewrapped, it.remaining, it.complete) }

    @GetMapping("/health")
    fun health(): HealthResponse = adminOperationsService.health().let {
        HealthResponse(it.database, it.migrations, it.masterKeyProvider, it.casVerifier)
    }

    @GetMapping("/audit-events")
    fun auditEvents(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        authentication: Authentication,
        request: HttpServletRequest,
    ): AuditPageResponse = adminOperationsService.readAudit(
        cursor, limit, authentication.name, requestId(request),
    ).let { page ->
        AuditPageResponse(
            page.events.map { AuditEventResponse(it.eventId, it.occurredAt, it.requestId, it.actionCode.name, it.resultCode.name) },
            page.nextCursor,
        )
    }

    private fun parseRevision(value: String): Long {
        val revision = when {
            value.matches(UNQUOTED_REVISION) -> value
            value.matches(QUOTED_REVISION) -> value.substring(1, value.length - 1)
            else -> null
        }
        return revision?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw com.upcpocket.backend.service.ApiException(com.upcpocket.backend.service.ApiErrorCode.VALIDATION_FAILED)
    }

    private fun requestId(request: HttpServletRequest): UUID =
        request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) as? UUID ?: UUID.randomUUID()

    private fun com.upcpocket.backend.service.AdminAccountSummary.toResponse() =
        AdminAccountResponse(accountId, state, credentialGeneration, gateRevision)

    companion object {
        private val UNQUOTED_REVISION = Regex("[1-9][0-9]*")
        private val QUOTED_REVISION = Regex("\\\"[1-9][0-9]*\\\"")
    }
}

data class KeyRotationRequest(val targetVersion: String, val expectedWriteVersion: String)

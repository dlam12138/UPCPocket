package com.upcpocket.backend.http

import com.upcpocket.backend.security.PocketPrincipal
import com.upcpocket.backend.service.StudentAccountService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/me")
class StudentAccountController(private val studentAccountService: StudentAccountService) {
    @GetMapping
    fun me(@AuthenticationPrincipal principal: PocketPrincipal): StudentAccountResponse =
        studentAccountService.me(principal.accountId).toStudentResponse()

    @GetMapping("/devices")
    fun devices(@AuthenticationPrincipal principal: PocketPrincipal): List<DeviceResponse> =
        studentAccountService.devices(principal.accountId).map {
            DeviceResponse(it.deviceId, it.installationId, it.platform, it.kind, it.expiresAt)
        }

    @DeleteMapping("/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun signOut(
        @AuthenticationPrincipal principal: PocketPrincipal,
    ) {
        studentAccountService.signOut(principal.identity)
    }

    @DeleteMapping("/devices/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revokeDevice(
        @AuthenticationPrincipal principal: PocketPrincipal,
        @PathVariable deviceId: UUID,
    ) {
        studentAccountService.revokeDevice(principal.accountId, deviceId)
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unbind(
        @AuthenticationPrincipal principal: PocketPrincipal,
        request: HttpServletRequest,
    ) {
        studentAccountService.unbind(
            principal.accountId,
            request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) as? UUID ?: UUID.randomUUID(),
        )
    }
}

private fun com.upcpocket.backend.service.StudentAccountSummary.toStudentResponse() =
    StudentAccountResponse(accountId, state, credentialGeneration, gateRevision)

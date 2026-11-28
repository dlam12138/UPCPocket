package com.upcpocket.backend.http

import com.upcpocket.backend.contract.DeviceDescriptor
import com.upcpocket.backend.service.StudentAccountService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/auth")
class StudentAuthController(private val studentAccountService: StudentAccountService) {
    @PostMapping("/sessions")
    fun createSession(
        @RequestBody body: SignInRequest,
        request: HttpServletRequest,
    ): SessionResponse {
        try {
            val issued = studentAccountService.createSession(
                loginName = body.loginName,
                password = body.password,
                acceptCustody = body.acceptCustody,
                device = DeviceDescriptor(body.installationId, body.platform, body.kind),
                clientIp = request.remoteAddr ?: "unknown",
                requestId = requestId(request),
            )
            return SessionResponse(issued.token, issued.deviceId, issued.expiresAt)
        } finally {
            body.loginName.fill('\u0000')
            body.password.fill('\u0000')
            RequestSensitiveCharArrays.clearCurrentRequest()
        }
    }

    @PostMapping("/credential-updates")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun updateCredential(
        @RequestBody body: CredentialUpdateRequest,
        request: HttpServletRequest,
    ) {
        try {
            studentAccountService.updateCredential(
                body.loginName,
                body.password,
                request.remoteAddr ?: "unknown",
                requestId(request),
            )
        } finally {
            body.loginName.fill('\u0000')
            body.password.fill('\u0000')
            RequestSensitiveCharArrays.clearCurrentRequest()
        }
    }

    private fun requestId(request: HttpServletRequest): UUID =
        request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) as? UUID ?: UUID.randomUUID()
}

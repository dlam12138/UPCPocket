package com.upcpocket.backend.http

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.upcpocket.backend.contract.ModuleSwitchStatus
import com.upcpocket.backend.service.ApiErrorCode
import com.upcpocket.backend.service.ApiException
import com.upcpocket.backend.service.ModuleSwitchService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/internal/admin/v1/modules")
class AdminModuleSwitchController(private val moduleSwitchService: ModuleSwitchService) {
    @GetMapping("/{moduleKey}/status")
    fun status(@PathVariable moduleKey: String): ResponseEntity<ModuleSwitchStatus> {
        val status = moduleSwitchService.status(moduleKey)
        return ResponseEntity.ok().header(HttpHeaders.ETAG, etag(status.revision)).body(status)
    }

    @PutMapping("/{moduleKey}/status")
    fun update(
        @PathVariable moduleKey: String,
        @RequestBody body: ModuleEnabledRequest,
        @RequestHeader("If-Match") ifMatch: String,
        authentication: Authentication,
        request: HttpServletRequest,
    ): ResponseEntity<ModuleSwitchStatus> {
        val status = moduleSwitchService.update(
            moduleKey = moduleKey,
            enabled = body.enabled,
            expectedRevision = parseRevision(ifMatch),
            actorSubject = authentication.name,
            requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) as? UUID ?: UUID.randomUUID(),
        )
        return ResponseEntity.ok().header(HttpHeaders.ETAG, etag(status.revision)).body(status)
    }

    private fun etag(revision: Long) = "\"$revision\""

    private fun parseRevision(value: String): Long {
        val raw = value.takeIf { it.matches(QUOTED_REVISION) }
            ?.substring(1, value.length - 1)
        return raw?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw ApiException(ApiErrorCode.VALIDATION_FAILED)
    }

    companion object {
        private val QUOTED_REVISION = Regex("\\\"[1-9][0-9]*\\\"")
    }
}

class ModuleEnabledRequest(val enabled: Boolean) {
    @JsonAnySetter
    fun rejectUnknownProperty(@Suppress("UNUSED_PARAMETER") name: String, @Suppress("UNUSED_PARAMETER") value: Any?) {
        throw ApiException(ApiErrorCode.VALIDATION_FAILED)
    }
}

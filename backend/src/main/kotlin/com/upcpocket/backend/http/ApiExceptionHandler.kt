package com.upcpocket.backend.http

import com.upcpocket.backend.service.ApiErrorCode
import com.upcpocket.backend.service.ApiException
import com.upcpocket.backend.session.SessionCapacityException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun handleApiException(error: ApiException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val requestId = request.requestId()
        val response = ResponseEntity.status(error.code.httpStatus)
        error.retryAfterSeconds?.let { response.header("Retry-After", it.toString()) }
        return response.body(ErrorResponse(error.code.name, requestId))
    }

    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        IllegalArgumentException::class,
        HttpMediaTypeNotSupportedException::class,
        MethodArgumentTypeMismatchException::class,
        MissingRequestHeaderException::class,
        MissingServletRequestParameterException::class,
    )
    fun handleBadRequest(@Suppress("UNUSED_PARAMETER") error: Exception, request: HttpServletRequest) =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ErrorResponse(ApiErrorCode.VALIDATION_FAILED.name, request.requestId()),
        )

    @ExceptionHandler(
        HttpRequestMethodNotSupportedException::class,
        NoHandlerFoundException::class,
        NoResourceFoundException::class,
    )
    fun handleNotFound(@Suppress("UNUSED_PARAMETER") error: Exception, request: HttpServletRequest) =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(ApiErrorCode.NOT_FOUND.name, request.requestId()),
        )

    @ExceptionHandler(SessionCapacityException::class)
    fun handleSessionCapacity(@Suppress("UNUSED_PARAMETER") error: SessionCapacityException, request: HttpServletRequest) =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
            ErrorResponse(ApiErrorCode.DEPENDENCY_UNAVAILABLE.name, request.requestId()),
        )

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(@Suppress("UNUSED_PARAMETER") error: Exception, request: HttpServletRequest) =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ErrorResponse(ApiErrorCode.INTERNAL_ERROR.name, request.requestId()),
        )

    private fun HttpServletRequest.requestId(): UUID =
        getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) as? UUID ?: UUID.randomUUID()
}

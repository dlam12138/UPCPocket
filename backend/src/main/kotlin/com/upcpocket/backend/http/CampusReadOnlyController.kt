package com.upcpocket.backend.http

import com.upcpocket.backend.contract.GradesCoursesData
import com.upcpocket.backend.contract.GradesTermsData
import com.upcpocket.backend.contract.TimetableIndexData
import com.upcpocket.backend.contract.TimetableWeekData
import com.upcpocket.backend.security.PocketPrincipal
import com.upcpocket.backend.service.CampusProxyService
import com.upcpocket.backend.service.CardBalanceResponse
import com.upcpocket.backend.service.CardTransactionsResponse
import com.upcpocket.backend.contract.CardTransactionsSummaryData
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class CampusReadOnlyController(private val campusProxyService: CampusProxyService) {
    @GetMapping("/timetable/index")
    fun timetableIndex(
        @AuthenticationPrincipal principal: PocketPrincipal,
        request: HttpServletRequest,
    ): TimetableIndexData = campusProxyService.timetableIndex(principal, requestId(request))

    @GetMapping("/timetable/week")
    fun timetableWeek(
        @AuthenticationPrincipal principal: PocketPrincipal,
        request: HttpServletRequest,
        @RequestParam year: String,
        @RequestParam term: String,
        @RequestParam week: Int,
    ): TimetableWeekData = campusProxyService.timetableWeek(principal, requestId(request), year, term, week)

    @GetMapping("/card/balance")
    fun cardBalance(
        @AuthenticationPrincipal principal: PocketPrincipal,
        request: HttpServletRequest,
    ): CardBalanceResponse = campusProxyService.cardBalance(principal, requestId(request))

    @GetMapping("/card/transactions")
    fun cardTransactions(
        @AuthenticationPrincipal principal: PocketPrincipal,
        request: HttpServletRequest,
        @RequestParam(required = false) month: String?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): CardTransactionsResponse = campusProxyService.cardTransactions(principal, requestId(request), month, page, size)

    @GetMapping("/card/transactions/summary")
    fun cardTransactionsSummary(
        @AuthenticationPrincipal principal: PocketPrincipal,
        request: HttpServletRequest,
        @RequestParam(required = false) month: String?,
    ): CardTransactionsSummaryData =
        campusProxyService.cardTransactionsSummary(principal, requestId(request), month)

    @GetMapping("/grades/terms")
    fun gradesTerms(
        @AuthenticationPrincipal principal: PocketPrincipal,
        request: HttpServletRequest,
    ): GradesTermsData = campusProxyService.gradesTerms(principal, requestId(request))

    @GetMapping("/grades/courses")
    fun gradesCourses(
        @AuthenticationPrincipal principal: PocketPrincipal,
        request: HttpServletRequest,
        @RequestParam year: String,
        @RequestParam term: String,
    ): GradesCoursesData = campusProxyService.gradesCourses(principal, requestId(request), year, term)

    private fun requestId(request: HttpServletRequest): UUID =
        request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) as? UUID ?: UUID.randomUUID()
}

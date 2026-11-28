package com.upcpocket.app.grades

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.grades.ui.GradesAccountState
import com.upcpocket.app.grades.ui.GradesCourse as DisplayCourse
import com.upcpocket.app.grades.ui.GradesCourseNature
import com.upcpocket.app.grades.ui.GradesLevel
import com.upcpocket.app.grades.ui.GradesScore
import com.upcpocket.app.grades.ui.GradesScreenContent
import com.upcpocket.app.grades.ui.GradesSyncFailure
import com.upcpocket.app.grades.ui.GradesSyncState
import com.upcpocket.app.grades.ui.GradesTerm as DisplayTerm
import com.upcpocket.app.grades.ui.GradesUiState as DisplayState
import kotlinx.coroutines.launch

@Composable
internal fun GradesScreen(
    account: SchoolAccountReferenceReader,
    service: GradesService,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenOfficialPage: () -> Unit,
) {
    val accountState by account.state.collectAsStateWithLifecycle()
    val reference by account.accountReference.collectAsStateWithLifecycle()
    val snapshot by service.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val owner = reference?.takeIf {
        accountState.isManagedActive && !accountState.blocksPersonalSessions &&
            accountState.generation == it.credentialGeneration
    }

    LaunchedEffect(service, owner) {
        if (owner != null) service.restore()
    }

    val display = when {
        accountState.status == SchoolAccountStatus.LOADING ->
            DisplayState(account = GradesAccountState.RESTORING)
        accountState.blocksPersonalSessions ->
            DisplayState(account = GradesAccountState.CLEANING)
        owner == null && accountState.isManagedActive ->
            DisplayState(account = GradesAccountState.RESTORING)
        owner == null ->
            DisplayState(account = GradesAccountState.ACCOUNT_REQUIRED)
        snapshot.owner != owner -> DisplayState()
        else -> remember(snapshot) { snapshot.toDisplayState() }
    }

    key(owner) {
        GradesScreenContent(
            state = display,
            onBack = onBack,
            onSync = { scope.launch { service.sync() } },
            onOpenAccount = onOpenAccount,
            onOpenOfficialPage = onOpenOfficialPage,
        )
    }
}

private fun GradesUiState.toDisplayState(): DisplayState {
    val sync = when (connection) {
        GradesConnection.LOADING,
        GradesConnection.NOT_CONFIGURED,
        GradesConnection.BLOCKED_BY_CLEANUP -> GradesSyncState.Loading
        GradesConnection.IDLE -> GradesSyncState.NotSynced
        GradesConnection.CONNECTING -> GradesSyncState.Syncing
        GradesConnection.READY -> GradesSyncState.Ready(lastSyncedAtEpochMillis)
        GradesConnection.PARTIAL,
        GradesConnection.INTERACTION_REQUIRED,
        GradesConnection.AUTH_FAILED,
        GradesConnection.NETWORK_UNAVAILABLE,
        GradesConnection.PROTOCOL_ERROR -> GradesSyncState.Failed(
            reason = lastFailure.toDisplayFailure(),
            retryAfterSeconds = retryAfterSeconds,
        )
    }
    val syncedTerms = termResults.mapTo(mutableSetOf()) { it.term }
    val failedTerms = (lastSyncRetainedTerms + lastSyncFailedTerms).toSet()
    val titleCounts = termResults.flatMap { it.courses }
        .groupingBy { it.courseName.trim() }
        .eachCount()

    return DisplayState(
        sync = sync,
        unsavedChanges = unsavedChanges,
        terms = terms.map { term ->
            DisplayTerm(
                id = term.key,
                label = term.displayLabel(),
                isSynced = term in syncedTerms,
                syncFailed = term in failedTerms,
            )
        },
        courses = termResults.flatMap { result ->
            result.courses.mapIndexed { index, course ->
                DisplayCourse(
                    id = "${result.term.key}:$index",
                    termId = result.term.key,
                    name = course.courseName,
                    rawGrade = course.score.orEmpty(),
                    score = parseScore(course.score.orEmpty()),
                    credits = course.credits,
                    nature = course.courseType.orEmpty().trim().toCourseNature(),
                    retakeAmbiguous = titleCounts.getValue(course.courseName.trim()) > 1 ||
                        course.examType?.let { "重修" in it || "补考" in it } == true,
                )
            }
        },
    )
}

private fun GradesTerm.displayLabel(): String {
    val termNumber = term.toIntOrNull()
    return if (termNumber == null) "$year $term" else "$year 第 ${termNumber} 学期"
}

private fun parseScore(raw: String): GradesScore {
    val value = raw.trim()
    if (value.isEmpty()) return GradesScore.Missing
    val numeric = value.toDoubleOrNull()
    if (numeric != null && numeric.isFinite() && numeric in 0.0..100.0) {
        return GradesScore.Percent(numeric)
    }
    GradesLevel.entries.firstOrNull { it.label == value }?.let { return GradesScore.Level(it) }
    return when (value) {
        "通过" -> GradesScore.PassFail(true)
        "不通过" -> GradesScore.PassFail(false)
        else -> GradesScore.Unrecognized
    }
}

private fun String.toCourseNature(): GradesCourseNature? {
    if (isEmpty()) return null
    val isRequired = when (this) {
        "必修", "必修课", "必修课程" -> true
        "选修", "选修课", "选修课程", "限选", "限选课", "任选", "任选课",
        "公共选修", "公共选修课", "专业选修", "专业选修课", "通识选修" -> false
        else -> null
    }
    return GradesCourseNature(this, isRequired)
}

private fun GradesFailure?.toDisplayFailure(): GradesSyncFailure = when (this) {
    GradesFailure.NETWORK_UNAVAILABLE,
    GradesFailure.TIMEOUT,
    GradesFailure.TLS_ERROR -> GradesSyncFailure.NETWORK
    GradesFailure.AUTH_INTERACTION -> GradesSyncFailure.INTERACTION
    GradesFailure.AUTH_INVALID,
    GradesFailure.AUTH_EXPIRED,
    GradesFailure.TOKEN_INVALID,
    GradesFailure.UNAUTHENTICATED,
    GradesFailure.DISABLED,
    GradesFailure.ACCESS_DENIED,
    GradesFailure.ACCOUNT_REQUIRED -> GradesSyncFailure.AUTH
    GradesFailure.SERVICE_NOT_READY -> GradesSyncFailure.SERVICE_NOT_READY
    GradesFailure.CREDENTIAL_UPDATE_REQUIRED -> GradesSyncFailure.CREDENTIAL_UPDATE_REQUIRED
    GradesFailure.VERIFICATION_BUSY -> GradesSyncFailure.VERIFICATION_BUSY
    GradesFailure.MODULE_DISABLED -> GradesSyncFailure.MODULE_DISABLED
    GradesFailure.CIRCUIT_OPEN -> GradesSyncFailure.CIRCUIT_OPEN
    GradesFailure.UPSTREAM_UNAVAILABLE,
    GradesFailure.DEPENDENCY_UNAVAILABLE -> GradesSyncFailure.UPSTREAM_UNAVAILABLE
    GradesFailure.UPSTREAM_TIMEOUT -> GradesSyncFailure.UPSTREAM_TIMEOUT
    GradesFailure.VALIDATION_FAILED -> GradesSyncFailure.VALIDATION_FAILED
    GradesFailure.IDENTITY_UNSUPPORTED -> GradesSyncFailure.IDENTITY_UNSUPPORTED
    GradesFailure.RATE_LIMITED -> GradesSyncFailure.RATE_LIMITED
    GradesFailure.UPSTREAM_ERROR,
    GradesFailure.PARTIAL_FAILURE -> GradesSyncFailure.SERVER
    GradesFailure.PROTOCOL_ERROR -> GradesSyncFailure.UNSUPPORTED
    GradesFailure.STORAGE_ERROR -> GradesSyncFailure.STORAGE
    GradesFailure.ACCOUNT_BLOCKED, null -> GradesSyncFailure.UNKNOWN
}

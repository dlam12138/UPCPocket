package com.upcpocket.app.serviceportal

import com.upcpocket.app.account.api.SchoolAccountReference

internal enum class ServicePortalFailure {
    INVALID_INPUT,
    INTERACTION_REQUIRED,
    INVALID_CREDENTIALS,
    NETWORK,
    TLS,
    UPSTREAM,
    PROTOCOL,
    ACCOUNT_UNAVAILABLE,
    ACCOUNT_CHANGED,
    ACCESS_DENIED,
    IDENTITY_MISMATCH,
    AUTH_REQUIRED,
    BUSY,
}

internal class ServicePortalException(val reason: ServicePortalFailure) :
    Exception(null, null, false, false)

internal enum class ServicePortalTab { SERVICES, TODOS, APPLICATIONS }
internal enum class ServicePortalScope { ACCOUNT, PUBLIC }
internal enum class ServicePortalAccess { NATIVE_FORM, EXTERNAL, MINI_PROGRAM, FORM_UNKNOWN, GUIDE_ONLY, UNKNOWN }
internal enum class ServicePortalMaterialsState { MISSING, EMPTY, AVAILABLE }
internal enum class ServicePortalLoginStatus { NO_ACCOUNT, IDLE, WORKING, SUCCEEDED, FAILED }

/** The source row index keeps duplicate service IDs visible and separately selectable. */
internal class ServicePortalItem(
    val rowKey: Int,
    internal val detailId: Long,
    val name: String,
    val department: String?,
    val access: ServicePortalAccess,
    val externalHost: String?,
    val idAmbiguous: Boolean,
) {
    override fun toString(): String =
        "ServicePortalItem(rowKey=$rowKey, access=$access, idAmbiguous=$idAmbiguous)"
}

internal data class ServicePortalCatalog(
    val items: List<ServicePortalItem>,
    val folderCount: Int,
    val source: ServicePortalScope,
) {
    override fun toString(): String =
        "ServicePortalCatalog(itemCount=${items.size}, folderCount=$folderCount, source=$source)"
}

internal data class ServicePortalSearchPage(
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val items: List<ServicePortalItem>,
    val hasMore: Boolean,
    val completeForPage: Boolean,
    val completeForScope: Boolean,
    val pageInRange: Boolean,
    val scope: ServicePortalScope,
) {
    override fun toString(): String =
        "ServicePortalSearchPage(total=$total, page=$page, pageSize=$pageSize, itemCount=${items.size}, " +
            "hasMore=$hasMore, completeForPage=$completeForPage, completeForScope=$completeForScope, " +
            "pageInRange=$pageInRange, scope=$scope)"
}

internal data class ServicePortalMaterial(
    val label: String,
    val value: String,
    val truncated: Boolean,
) {
    override fun toString(): String = "ServicePortalMaterial([REDACTED])"
}

internal data class ServicePortalGuide(
    val title: String,
    val department: String?,
    val description: String,
    val descriptionTruncated: Boolean,
    val materials: List<ServicePortalMaterial>,
    val materialsState: ServicePortalMaterialsState,
    val materialsTruncated: Boolean,
    val handlingPlace: String?,
    val handlingTimes: String?,
    val chargeType: String?,
    val charge: String?,
    val formCount: Int?,
    val startNodes: List<String>,
    val access: ServicePortalAccess,
    val externalHost: String?,
    val externalUrl: String?,
    val selectionMayBeAmbiguous: Boolean,
    val scope: ServicePortalScope,
) {
    override fun toString(): String =
        "ServicePortalGuide(scope=$scope, materials=${materials.size}, selectionMayBeAmbiguous=$selectionMayBeAmbiguous)"
}

internal data class ServicePortalTodo(
    val rowKey: String,
    val title: String,
    val status: String?,
    val currentNode: String?,
    val createdAt: String?,
    val thirdParty: Boolean,
) {
    override fun toString(): String = "ServicePortalTodo([REDACTED])"
}

internal data class ServicePortalProgress(val percent: Int, val nodeCount: Int)

internal data class ServicePortalApplication(
    val rowKey: String,
    val title: String,
    val status: String?,
    val statusCode: Int?,
    val createdAt: String?,
    val thirdParty: Boolean,
    val progress: ServicePortalProgress?,
) {
    override fun toString(): String = "ServicePortalApplication([REDACTED])"
}

internal data class ServicePortalDataPage<T>(
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val items: List<T>,
    val hasMore: Boolean,
    val completeForPage: Boolean,
    val completeForScope: Boolean,
    val pageInRange: Boolean,
    val scope: ServicePortalScope,
) {
    override fun toString(): String =
        "ServicePortalDataPage(total=$total, page=$page, pageSize=$pageSize, itemCount=${items.size}, " +
            "hasMore=$hasMore, completeForPage=$completeForPage, completeForScope=$completeForScope, " +
            "pageInRange=$pageInRange, scope=$scope)"
}

internal data class ServicePortalState(
    /** In-memory reset marker; it never contains an account identifier. */
    val accountEpoch: Long = 0,
    val accountAvailable: Boolean = false,
    val accountBlocked: Boolean = false,
    val loginStatus: ServicePortalLoginStatus = ServicePortalLoginStatus.NO_ACCOUNT,
    val tab: ServicePortalTab = ServicePortalTab.SERVICES,
    val keyword: String = "",
    val searchPage: Int = 1,
    val pageSize: Int = 20,
    val catalog: ServicePortalCatalog? = null,
    val results: ServicePortalSearchPage? = null,
    val selected: ServicePortalItem? = null,
    val guide: ServicePortalGuide? = null,
    val todos: ServicePortalDataPage<ServicePortalTodo>? = null,
    val applications: ServicePortalDataPage<ServicePortalApplication>? = null,
    val busy: Boolean = false,
    val error: ServicePortalFailure? = null,
    val refreshFailed: Boolean = false,
    val authenticationNotice: ServicePortalFailure? = null,
) {
    override fun toString(): String =
        "ServicePortalState(accountAvailable=$accountAvailable, tab=$tab, busy=$busy, error=$error, accountEpoch=$accountEpoch)"
}

internal class ServicePortalOwner(val reference: SchoolAccountReference) {
    override fun equals(other: Any?): Boolean =
        other is ServicePortalOwner && reference == other.reference

    override fun hashCode(): Int = reference.hashCode()

    override fun toString(): String = "ServicePortalOwner([REDACTED])"
}

internal class ServicePortalToken(
    val owner: ServicePortalOwner?,
    val businessEpoch: Long,
    val selectionVersion: Long,
)

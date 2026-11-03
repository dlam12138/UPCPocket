package com.upcpocket.app.account

import com.upcpocket.app.account.api.AccountDataScope
import com.upcpocket.app.account.api.AccountInvalidationReason
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountErrorCode
import kotlinx.serialization.Serializable

internal object AccountFormat {
    const val CREDENTIAL_FORMAT = "upcpocket.school-account.credential"
    const val STATE_FORMAT = "upcpocket.school-account.state"
    const val JOURNAL_FORMAT = "upcpocket.school-account.transition"
    const val VERSION = 1

    const val ACCOUNT_FILE = "school-account-v1.enc"
    const val PENDING_FILE = "school-account-pending-v1.enc"
    const val STATE_FILE = "school-account-state-v1.json"
    const val JOURNAL_FILE = "school-account-transition-v1.json"

    const val MAX_CREDENTIAL_BYTES = 64 * 1024
    const val MAX_STATE_BYTES = 8 * 1024
    const val MAX_JOURNAL_BYTES = 8 * 1024
    const val KEY_ALIAS = "upcpocket.school-account.v1"
}

@Serializable
internal data class CredentialEnvelope(
    val format: String = AccountFormat.CREDENTIAL_FORMAT,
    val version: Int = AccountFormat.VERSION,
    val ciphertext: String,
)

@Serializable
internal data class CredentialPayload(
    val version: Int = AccountFormat.VERSION,
    val generation: Long,
    val savedAt: Long,
    val account: String,
    val password: String,
) {
    /** 防止任何日志/崩溃框架把凭据内容带出去。 */
    override fun toString(): String =
        "CredentialPayload(version=$version, generation=$generation, account=[REDACTED], password=[REDACTED])"
}

internal enum class PersistedStatus {
    NOT_CONFIGURED,
    ACTIVE,
    NEEDS_RECONFIGURATION,
    SWITCHING,
    UPDATING,
    REMOVING,
}

@Serializable
internal data class PersistedAccountState(
    val format: String = AccountFormat.STATE_FORMAT,
    val version: Int = AccountFormat.VERSION,
    val status: String = PersistedStatus.NOT_CONFIGURED.name,
    val generation: Long = 0,
    val savedAt: Long? = null,
    val cleanupRequired: Boolean = false,
    val errorCode: String? = null,
) {
    fun persistedStatus(): PersistedStatus? =
        PersistedStatus.entries.firstOrNull { it.name == status }
}

internal enum class TransitionOperation {
    SAVE,
    DELETE,
    RECOVER,
}

@Serializable
internal data class TransitionJournal(
    val format: String = AccountFormat.JOURNAL_FORMAT,
    val version: Int = AccountFormat.VERSION,
    val operation: String,
    val reason: String,
    val sourceGeneration: Long,
    val targetGeneration: Long,
    val scope: String? = null,
    val pendingCredential: Boolean,
    val errorCode: String? = null,
) {
    fun parsedOperation(): TransitionOperation? =
        TransitionOperation.entries.firstOrNull { it.name == operation }

    fun parsedReason(): AccountInvalidationReason? =
        AccountInvalidationReason.entries.firstOrNull { it.name == reason }

    fun parsedScope(): AccountDataScope? =
        AccountDataScope.entries.firstOrNull { it.name == scope }
}

internal class AccountStorageException(val code: SchoolAccountErrorCode) :
    Exception(null, null, false, false)

internal class AccountReadException(val code: SchoolAccountErrorCode) :
    Exception(null, null, false, false)

internal fun AccountGeneration.incremented(): AccountGeneration =
    AccountGeneration(value + 1)

internal fun SchoolAccountErrorCode.asString(): String = name

internal fun String.toErrorCodeOrNull(): SchoolAccountErrorCode? =
    SchoolAccountErrorCode.entries.firstOrNull { it.name == this }

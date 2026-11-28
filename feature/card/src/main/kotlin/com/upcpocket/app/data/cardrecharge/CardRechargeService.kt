package com.upcpocket.app.data.cardrecharge

import com.upcpocket.app.account.api.AccountDataScope
import java.io.File

/**
 * Compatibility cleanup surface for legacy encrypted recharge records.
 *
 * Recharge preparation, order creation, and payment are not part of the card plugin. The class is
 * never registered as a capability; the host may still call [clearStoredData] during account
 * cleanup so an upgrade cannot leave an old pending payment marker behind.
 */
class CardRechargeService private constructor() {
    suspend fun invalidateForAccountChange(scope: AccountDataScope): Boolean = true

    companion object {
        fun clearStoredData(noBackupDirectory: File): Boolean = runCatching {
            val root = noBackupDirectory.canonicalFile
            val legacy = File(root, LEGACY_DIRECTORY).canonicalFile
            if (legacy.parentFile != root) return@runCatching false
            if (legacy.exists() && !legacy.deleteRecursively()) return@runCatching false
            !legacy.exists()
        }.getOrDefault(false)

        private const val LEGACY_DIRECTORY = "card-recharge"
    }
}

package com.upcpocket.app.account

import java.io.File
import java.security.KeyStore

/** Deletes the old local-password key and exactly the known AtomicFile artifacts. */
internal class LegacySchoolCredentialPurger(
    private val directory: File,
    private val aliases: LegacyAliasStore = AndroidLegacyAliasStore,
) {
    fun purge(): Boolean = runCatching {
        aliases.delete(LEGACY_KEY_ALIAS)
        if (aliases.contains(LEGACY_KEY_ALIAS)) return false
        val files = legacyNames.flatMap { name ->
            listOf(name, "$name.bak", "$name.new")
        }.map { File(directory, it) }
        files.forEach { file ->
            if (file.exists() && !file.delete()) return false
        }
        files.none(File::exists)
    }.getOrDefault(false)

    internal companion object {
        const val LEGACY_KEY_ALIAS = "upcpocket.school-account.v1"
        val legacyNames = listOf(
            "school-account-v1.enc",
            "school-account-pending-v1.enc",
            "school-account-state-v1.json",
            "school-account-transition-v1.json",
        )
    }
}

internal interface LegacyAliasStore {
    fun contains(alias: String): Boolean
    fun delete(alias: String)
}

private object AndroidLegacyAliasStore : LegacyAliasStore {
    override fun contains(alias: String): Boolean =
        KeyStore.getInstance("AndroidKeyStore").run {
            load(null)
            containsAlias(alias)
        }

    override fun delete(alias: String) {
        KeyStore.getInstance("AndroidKeyStore").run {
            load(null)
            if (containsAlias(alias)) deleteEntry(alias)
        }
    }
}

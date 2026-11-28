package com.upcpocket.app.account

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Legacy AtomicFile cleanup removes only the old-password paths and their recovery siblings. */
class AtomicAccountFileStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun purgerRemovesEveryLegacyPasswordArtifactAndOldAlias() {
        val directory = temporary.newFolder("school-account")
        val legacyFiles = LegacySchoolCredentialPurger.legacyNames.flatMap { name ->
            listOf(name, "$name.bak", "$name.new")
        }.map { name -> File(directory, name).apply { writeText("synthetic-old-ciphertext") } }
        val alias = LegacySchoolCredentialPurger.LEGACY_KEY_ALIAS
        val aliases = FakeAliases(mutableSetOf(alias))

        assertTrue(LegacySchoolCredentialPurger(directory, aliases).purge())
        assertFalse(aliases.contains(alias))
        assertTrue(legacyFiles.none(File::exists))
    }

    @Test
    fun aliasDeletionFailureLeavesArtifactsForAVisibleRetry() {
        val directory = temporary.newFolder("school-account-retry")
        val old = File(directory, LegacySchoolCredentialPurger.legacyNames.first()).apply {
            writeText("synthetic-old-ciphertext")
        }
        val aliases = object : LegacyAliasStore {
            override fun contains(alias: String): Boolean = true
            override fun delete(alias: String) = Unit
        }

        assertFalse(LegacySchoolCredentialPurger(directory, aliases).purge())
        assertTrue(old.isFile)
    }

    private class FakeAliases(private val values: MutableSet<String>) : LegacyAliasStore {
        override fun contains(alias: String): Boolean = alias in values
        override fun delete(alias: String) { values.remove(alias) }
    }
}

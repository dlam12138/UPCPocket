package com.upcpocket.app.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/** Retained marker for the former local-school-password Keystore acceptance flow. */
@RunWith(AndroidJUnit4::class)
class RealKeystoreAccountTest {
    @Ignore("Local school-password storage was removed; managed-session tests now run in core:account.")
    @Test
    fun localSchoolPasswordSaveRestoreUpdateAndDeleteWasRemoved() = Unit
}

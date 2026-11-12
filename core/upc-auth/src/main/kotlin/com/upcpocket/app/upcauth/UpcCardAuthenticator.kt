package com.upcpocket.app.upcauth

import com.upcpocket.app.account.RestrictedSchoolCredentialUse
import com.upcpocket.app.account.SchoolCredentialFailure
import com.upcpocket.app.account.SchoolCredentialAccess
import com.upcpocket.app.account.SchoolCredentialUseResult
import com.upcpocket.app.account.api.SchoolAccountId
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class UpcCardAuthFailure {
    ACCOUNT_UNAVAILABLE, ACCOUNT_CHANGED, INTERACTION_REQUIRED, INVALID_CREDENTIALS,
    NETWORK_UNAVAILABLE, TLS_ERROR, PROTOCOL_ERROR,
}

sealed interface UpcCardAuthResult {
    class Success(val token: String) : UpcCardAuthResult {
        override fun toString() = "UpcCardAuthResult.Success([REDACTED])"
    }
    data class Failure(val reason: UpcCardAuthFailure) : UpcCardAuthResult
}

/** Uses the existing restricted credential boundary. No feature receives the school password. */
class UpcCardAuthenticator internal constructor(
    private val credentials: SchoolCredentialAccess,
    private val transport: UpcAuthTransport,
) {
    constructor(credentials: SchoolCredentialAccess) : this(credentials, OkHttpUpcAuthTransport())

    @OptIn(RestrictedSchoolCredentialUse::class)
    suspend fun authenticate(expectedAccountId: SchoolAccountId, expectedCredentialGeneration: Long): UpcCardAuthResult =
        withContext(Dispatchers.IO) {
            try {
                val result = credentials.withLoginCredential { credential ->
                    if (credential.accountId != expectedAccountId || credential.credentialGeneration.value != expectedCredentialGeneration) {
                        UpcCardAuthResult.Failure(UpcCardAuthFailure.ACCOUNT_CHANGED)
                    } else {
                        val flow = UpcCardLoginFlow(transport)
                        val form = flow.begin()
                        if (form.captchaId != null) UpcCardAuthResult.Failure(UpcCardAuthFailure.INTERACTION_REQUIRED)
                        else credential.usePassword { password ->
                            UpcCardAuthResult.Success(flow.login(form, credential.loginName, String(password), ""))
                        }
                    }
                }
                when (result) {
                    is SchoolCredentialUseResult.Success -> result.value
                    is SchoolCredentialUseResult.Failure -> UpcCardAuthResult.Failure(
                        if (result.reason == SchoolCredentialFailure.CHANGED_DURING_USE) UpcCardAuthFailure.ACCOUNT_CHANGED
                        else UpcCardAuthFailure.ACCOUNT_UNAVAILABLE,
                    )
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: CardCasRejected) {
                UpcCardAuthResult.Failure(if (error.form.captchaId != null) UpcCardAuthFailure.INTERACTION_REQUIRED else UpcCardAuthFailure.INVALID_CREDENTIALS)
            } catch (error: UpcAuthTransportException) {
                UpcCardAuthResult.Failure(if (error.failure == UpcAuthTransportFailure.TLS) UpcCardAuthFailure.TLS_ERROR else UpcCardAuthFailure.NETWORK_UNAVAILABLE)
            } catch (_: IOException) {
                UpcCardAuthResult.Failure(UpcCardAuthFailure.PROTOCOL_ERROR)
            } catch (_: IllegalArgumentException) {
                UpcCardAuthResult.Failure(UpcCardAuthFailure.PROTOCOL_ERROR)
            }
        }
}

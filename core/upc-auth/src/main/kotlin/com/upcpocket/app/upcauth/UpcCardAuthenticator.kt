package com.upcpocket.app.upcauth

import com.upcpocket.app.account.RestrictedSchoolCredentialUse
import com.upcpocket.app.account.SchoolCredentialFailure
import com.upcpocket.app.account.SchoolCredentialAccess
import com.upcpocket.app.account.SchoolCredentialUseResult
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticBindingSource
import com.upcpocket.app.platform.diagnostics.DiagnosticCallbackState
import com.upcpocket.app.platform.diagnostics.DiagnosticCredentialState
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticOperation
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.Diagnostics
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class UpcCardAuthFailure {
    ACCOUNT_UNAVAILABLE, ACCOUNT_CHANGED, INTERACTION_REQUIRED, INVALID_CREDENTIALS,
    NETWORK_UNAVAILABLE, TLS_ERROR, PROTOCOL_ERROR, ACCESS_DENIED, UPSTREAM_ERROR,
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
            val operation = Diagnostics.beginOperation()
            cardDiagnostic(
                operation, DiagnosticAction.AUTH_START, DiagnosticResult.PENDING,
                DiagnosticPhase.CREDENTIAL_RESTORE, DiagnosticDetail.AUTH_REQUIRED,
            )
            try {
                val result = credentials.withLoginCredential { credential ->
                    if (credential.accountId != expectedAccountId || credential.credentialGeneration.value != expectedCredentialGeneration) {
                        cardDiagnostic(
                            operation, DiagnosticAction.AUTH_STOP, DiagnosticResult.BLOCKED,
                            DiagnosticPhase.CREDENTIAL_RESTORE, DiagnosticDetail.CREDENTIAL_GENERATION_CHANGED,
                            reason = DiagnosticReason.ACCOUNT,
                            credentialState = DiagnosticCredentialState.GENERATION_CHANGED,
                        )
                        UpcCardAuthResult.Failure(UpcCardAuthFailure.ACCOUNT_CHANGED)
                    } else {
                        cardDiagnostic(
                            operation, DiagnosticAction.AUTH_STAGE, DiagnosticResult.OK,
                            DiagnosticPhase.CREDENTIAL_RESTORE, DiagnosticDetail.CREDENTIAL_RECOVERED,
                            credentialState = DiagnosticCredentialState.AVAILABLE,
                        )
                        val flow = UpcCardLoginFlow(transport, operation = operation)
                        val form = flow.begin()
                        if (form.interactionRequired) UpcCardAuthResult.Failure(UpcCardAuthFailure.INTERACTION_REQUIRED)
                        else credential.usePassword { password ->
                            UpcCardAuthResult.Success(flow.login(form, credential.loginName, String(password), ""))
                        }
                    }
                }
                when (result) {
                    is SchoolCredentialUseResult.Success -> result.value
                    is SchoolCredentialUseResult.Failure -> {
                        val changed = result.reason == SchoolCredentialFailure.CHANGED_DURING_USE
                        val cleanupBlocked = result.reason == SchoolCredentialFailure.BLOCKED_BY_CLEANUP
                        cardDiagnostic(
                            operation, DiagnosticAction.AUTH_STOP, DiagnosticResult.BLOCKED,
                            DiagnosticPhase.CREDENTIAL_RESTORE,
                            when {
                                changed -> DiagnosticDetail.CREDENTIAL_GENERATION_CHANGED
                                cleanupBlocked -> DiagnosticDetail.CREDENTIAL_CLEANUP_BLOCKED
                                else -> DiagnosticDetail.CREDENTIAL_UNAVAILABLE
                            },
                            reason = DiagnosticReason.ACCOUNT,
                            credentialState = when {
                                changed -> DiagnosticCredentialState.GENERATION_CHANGED
                                cleanupBlocked -> DiagnosticCredentialState.CLEANUP_BLOCKED
                                else -> DiagnosticCredentialState.UNAVAILABLE
                            },
                        )
                        UpcCardAuthResult.Failure(
                            if (changed) UpcCardAuthFailure.ACCOUNT_CHANGED else UpcCardAuthFailure.ACCOUNT_UNAVAILABLE,
                        )
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: CardCasInteractionRequired) {
                UpcCardAuthResult.Failure(UpcCardAuthFailure.INTERACTION_REQUIRED)
            }
            catch (error: CardCasHttpFailure) {
                UpcCardAuthResult.Failure(error.authFailure)
            }
            catch (error: CardCasRejected) {
                UpcCardAuthResult.Failure(if (error.form.interactionRequired) UpcCardAuthFailure.INTERACTION_REQUIRED else UpcCardAuthFailure.INVALID_CREDENTIALS)
            } catch (error: UpcAuthTransportException) {
                UpcCardAuthResult.Failure(error.toCardFailure())
            } catch (_: IOException) {
                cardDiagnostic(
                    operation, DiagnosticAction.AUTH_STOP, DiagnosticResult.FAIL,
                    DiagnosticPhase.CAS_FORM, DiagnosticDetail.CAS_FORM_REJECTED,
                    reason = DiagnosticReason.SCHEMA,
                )
                UpcCardAuthResult.Failure(UpcCardAuthFailure.PROTOCOL_ERROR)
            } catch (_: IllegalArgumentException) {
                UpcCardAuthResult.Failure(UpcCardAuthFailure.PROTOCOL_ERROR)
            }
        }

    /** Completes only a verified, current-account result intercepted by the card WebView. */
    @OptIn(RestrictedSchoolCredentialUse::class)
    suspend fun completeWebLoginResult(
        expectedAccountId: SchoolAccountId,
        expectedCredentialGeneration: Long,
        resultUrl: String,
        operation: DiagnosticOperation? = null,
    ): UpcCardAuthResult = withContext(Dispatchers.IO) {
        val activeOperation = operation ?: Diagnostics.beginOperation()
        cardDiagnostic(
            activeOperation, DiagnosticAction.AUTH_START, DiagnosticResult.PENDING,
            DiagnosticPhase.WEBVIEW_CALLBACK, DiagnosticDetail.AUTH_REQUIRED,
            bindingSource = DiagnosticBindingSource.VERIFIED_WEB,
            callbackState = DiagnosticCallbackState.ACCEPTED,
        )
        try {
            val result = credentials.withLoginCredential { credential ->
                if (credential.accountId != expectedAccountId ||
                    credential.credentialGeneration.value != expectedCredentialGeneration
                ) {
                    cardDiagnostic(
                        activeOperation, DiagnosticAction.AUTH_STOP, DiagnosticResult.BLOCKED,
                        DiagnosticPhase.CREDENTIAL_RESTORE, DiagnosticDetail.CREDENTIAL_GENERATION_CHANGED,
                        reason = DiagnosticReason.ACCOUNT,
                        credentialState = DiagnosticCredentialState.GENERATION_CHANGED,
                        bindingSource = DiagnosticBindingSource.VERIFIED_WEB,
                    )
                    UpcCardAuthResult.Failure(UpcCardAuthFailure.ACCOUNT_CHANGED)
                } else {
                    cardDiagnostic(
                        activeOperation, DiagnosticAction.AUTH_STAGE, DiagnosticResult.OK,
                        DiagnosticPhase.CREDENTIAL_RESTORE, DiagnosticDetail.CREDENTIAL_RECOVERED,
                        credentialState = DiagnosticCredentialState.AVAILABLE,
                        bindingSource = DiagnosticBindingSource.VERIFIED_WEB,
                    )
                    UpcCardAuthResult.Success(
                        UpcCardLoginFlow(transport, operation = activeOperation)
                            .completeLoginResult(resultUrl, DiagnosticBindingSource.VERIFIED_WEB),
                    )
                }
            }
            when (result) {
                is SchoolCredentialUseResult.Success -> result.value
                is SchoolCredentialUseResult.Failure -> {
                    val changed = result.reason == SchoolCredentialFailure.CHANGED_DURING_USE
                    val cleanupBlocked = result.reason == SchoolCredentialFailure.BLOCKED_BY_CLEANUP
                    cardDiagnostic(
                        activeOperation, DiagnosticAction.AUTH_STOP, DiagnosticResult.BLOCKED,
                        DiagnosticPhase.CREDENTIAL_RESTORE,
                        when {
                            changed -> DiagnosticDetail.CREDENTIAL_GENERATION_CHANGED
                            cleanupBlocked -> DiagnosticDetail.CREDENTIAL_CLEANUP_BLOCKED
                            else -> DiagnosticDetail.CREDENTIAL_UNAVAILABLE
                        },
                        reason = DiagnosticReason.ACCOUNT,
                        credentialState = when {
                            changed -> DiagnosticCredentialState.GENERATION_CHANGED
                            cleanupBlocked -> DiagnosticCredentialState.CLEANUP_BLOCKED
                            else -> DiagnosticCredentialState.UNAVAILABLE
                        },
                        bindingSource = DiagnosticBindingSource.VERIFIED_WEB,
                    )
                    UpcCardAuthResult.Failure(
                        if (changed) UpcCardAuthFailure.ACCOUNT_CHANGED else UpcCardAuthFailure.ACCOUNT_UNAVAILABLE,
                    )
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: CardCasHttpFailure) {
            UpcCardAuthResult.Failure(error.authFailure)
        }
        catch (error: UpcAuthTransportException) {
            UpcCardAuthResult.Failure(error.toCardFailure())
        } catch (_: IOException) {
            cardDiagnostic(
                activeOperation, DiagnosticAction.AUTH_STOP, DiagnosticResult.FAIL,
                DiagnosticPhase.WEBVIEW_CALLBACK, DiagnosticDetail.WEBVIEW_CALLBACK_REJECTED,
                reason = DiagnosticReason.SCHEMA, callbackState = DiagnosticCallbackState.REJECTED,
                bindingSource = DiagnosticBindingSource.VERIFIED_WEB,
            )
            UpcCardAuthResult.Failure(UpcCardAuthFailure.PROTOCOL_ERROR)
        } catch (_: IllegalArgumentException) {
            UpcCardAuthResult.Failure(UpcCardAuthFailure.PROTOCOL_ERROR)
        }
    }
}

private fun UpcAuthTransportException.toCardFailure(): UpcCardAuthFailure = when (failure) {
    UpcAuthTransportFailure.TLS -> UpcCardAuthFailure.TLS_ERROR
    UpcAuthTransportFailure.NETWORK, UpcAuthTransportFailure.TIMEOUT -> UpcCardAuthFailure.NETWORK_UNAVAILABLE
    UpcAuthTransportFailure.TOO_LARGE, UpcAuthTransportFailure.PROTOCOL -> UpcCardAuthFailure.UPSTREAM_ERROR
}

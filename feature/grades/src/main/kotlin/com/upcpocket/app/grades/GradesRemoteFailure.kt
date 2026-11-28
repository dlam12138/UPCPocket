package com.upcpocket.app.grades

/** Legacy school-protocol parser failures retained for strict parser fixture tests only. */
internal enum class GradesRemoteFailure {
    AUTH_REQUIRED,
    NETWORK,
    TIMEOUT,
    TLS,
    TOO_LARGE,
    SCHEMA,
    PROTOCOL,
    HTTP,
    UPSTREAM,
}

internal class GradesRemoteException(
    val failure: GradesRemoteFailure,
) : Exception(failure.name, null, false, false)

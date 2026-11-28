package com.upcpocket.backend.security

import com.upcpocket.backend.session.PocketSessionIdentity
import java.util.UUID

data class PocketPrincipal(val identity: PocketSessionIdentity) {
    val accountId: UUID get() = identity.accountId
}

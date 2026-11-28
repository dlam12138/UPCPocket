package com.upcpocket.backend.cas

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "pocket.cas")
class CasProperties {
    var enabled: Boolean = false
    var loginUrl: String? = null
    var service: String? = null
    var connectTimeoutMs: Long = 3_000
    var readTimeoutMs: Long = 5_000
}

package com.upcpocket.backend.vault

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "pocket.vault")
class VaultProperties {
    var masterKeys: String? = null
    var masterKeysFile: String? = null
    var initialWriteKeyVersion: String? = null
    var loginIndexKey: String? = null
    var loginIndexKeyFile: String? = null
    var auditIndexKey: String? = null
    var auditIndexKeyFile: String? = null
}

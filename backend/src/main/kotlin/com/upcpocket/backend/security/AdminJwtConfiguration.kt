package com.upcpocket.backend.security

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.net.URI

@Configuration
class AdminJwtConfiguration {
    @Bean
    @ConditionalOnMissingBean(JwtDecoder::class)
    @ConditionalOnProperty(prefix = "pocket.admin.jwt", name = ["jwk-set-uri", "issuer", "audience"])
    fun adminJwtDecoder(
        @org.springframework.beans.factory.annotation.Value("\${pocket.admin.jwt.jwk-set-uri}") jwkSetUri: String,
        @org.springframework.beans.factory.annotation.Value("\${pocket.admin.jwt.issuer}") issuer: String,
        @org.springframework.beans.factory.annotation.Value("\${pocket.admin.jwt.audience}") audience: String,
    ): JwtDecoder {
        require(isSecureHttpsEndpoint(jwkSetUri) && isSecureHttpsEndpoint(issuer)) {
            "Admin JWT issuer and JWK set endpoint must use HTTPS"
        }
        val decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()
        val audienceAndMfa = OAuth2TokenValidator<Jwt> { jwt ->
            val hasAudience = audience in jwt.audience
            val hasExpiry = jwt.expiresAt != null
            val amr = jwt.claims["amr"]
            val hasMfa = jwt.claims["mfa"] == true ||
                (amr is Collection<*> && amr.any { it == "mfa" }) || amr == "mfa"
            if (hasAudience && hasExpiry && hasMfa) OAuth2TokenValidatorResult.success()
            else OAuth2TokenValidatorResult.failure(
                OAuth2Error("invalid_token", "Required audience, expiry or MFA claim is missing", null),
            )
        }
        decoder.setJwtValidator(
            org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator(
                JwtValidators.createDefaultWithIssuer(issuer),
                audienceAndMfa,
            ),
        )
        return decoder
    }

    private fun isSecureHttpsEndpoint(value: String): Boolean = runCatching { URI(value) }
        .getOrNull()
        ?.let { it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank() && it.userInfo == null }
        ?: false
}

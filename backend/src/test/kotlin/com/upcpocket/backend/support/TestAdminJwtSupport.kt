package com.upcpocket.backend.support

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.jwt.JwsHeader
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Instant

object TestAdminJwtSupport {
    const val ISSUER = "https://test-admin.upcpocket.invalid"
    const val AUDIENCE = "upcpocket-admin-test"
    private val keyPair: KeyPair by lazy {
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }

    fun token(vararg scopes: String, mfa: Boolean = true, expiresAt: Instant = Instant.now().plusSeconds(300)): String {
        val rsaKey = RSAKey.Builder(keyPair.public as java.security.interfaces.RSAPublicKey)
            .privateKey(keyPair.private as java.security.interfaces.RSAPrivateKey)
            .keyID("test-admin-key")
            .build()
        val encoder: JwtEncoder = NimbusJwtEncoder(ImmutableJWKSet<SecurityContext>(JWKSet(rsaKey)))
        val now = Instant.now()
        val claims = JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject("test-admin")
            .audience(listOf(AUDIENCE))
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim("scope", scopes.joinToString(" "))
            .claim("amr", if (mfa) listOf("pwd", "mfa") else listOf("pwd"))
            .build()
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-admin-key").build(), claims)).tokenValue
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Configuration {
        @Bean
        @Primary
        fun testAdminJwtDecoder(): JwtDecoder {
            val decoder = NimbusJwtDecoder.withPublicKey(TestAdminJwtSupport.keyPair.public as java.security.interfaces.RSAPublicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build()
            val custom = OAuth2TokenValidator<Jwt> { jwt ->
                val validMfa = jwt.claims["mfa"] == true ||
                    (jwt.claims["amr"] as? Collection<*>)?.contains("mfa") == true
                if (TestAdminJwtSupport.AUDIENCE in jwt.audience && jwt.expiresAt != null && validMfa) {
                    OAuth2TokenValidatorResult.success()
                } else {
                    OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "Test audience, expiry or MFA is invalid", null))
                }
            }
            decoder.setJwtValidator(
                org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator(
                    JwtValidators.createDefaultWithIssuer(TestAdminJwtSupport.ISSUER), custom,
                ),
            )
            return decoder
        }
    }
}

package com.upcpocket.backend.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.upcpocket.backend.contract.AccountStore
import com.upcpocket.backend.session.InMemorySessionRegistry
import com.upcpocket.backend.service.ApiErrorCode
import com.upcpocket.backend.http.ErrorResponse
import com.upcpocket.backend.http.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import java.util.UUID

@Configuration
class PocketSecurityConfiguration {
    @Bean
    @Order(1)
    fun adminSecurityFilterChain(http: HttpSecurity, mapper: ObjectMapper): SecurityFilterChain {
        http.securityMatcher("/internal/admin/v1/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling {
                it.authenticationEntryPoint(jsonEntryPoint(mapper))
                    .accessDeniedHandler(jsonDeniedHandler(mapper))
            }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(HttpMethod.POST, "/internal/admin/v1/account-lookups").hasAuthority("SCOPE_account.read")
                    .requestMatchers(HttpMethod.PUT, "/internal/admin/v1/accounts/*/disabled").hasAuthority("SCOPE_account.manage")
                    .requestMatchers(HttpMethod.DELETE, "/internal/admin/v1/accounts/*/sessions").hasAuthority("SCOPE_account.manage")
                    .requestMatchers(HttpMethod.GET, "/internal/admin/v1/modules/*/status").hasAuthority("SCOPE_module.read")
                    .requestMatchers(HttpMethod.PUT, "/internal/admin/v1/modules/*/status").hasAuthority("SCOPE_module.manage")
                    .requestMatchers(HttpMethod.POST, "/internal/admin/v1/key-rotations").hasAuthority("SCOPE_key.rotate")
                    .requestMatchers(HttpMethod.GET, "/internal/admin/v1/health").hasAuthority("SCOPE_health.read")
                    .requestMatchers(HttpMethod.GET, "/internal/admin/v1/audit-events").hasAuthority("SCOPE_audit.read")
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { resource ->
                resource.jwt(Customizer.withDefaults())
                    .authenticationEntryPoint(jsonEntryPoint(mapper))
                    .accessDeniedHandler(jsonDeniedHandler(mapper))
            }
        return http.build()
    }

    @Bean
    @Order(2)
    fun studentSecurityFilterChain(
        http: HttpSecurity,
        sessions: InMemorySessionRegistry,
        accounts: AccountStore,
        mapper: ObjectMapper,
    ): SecurityFilterChain {
        http.securityMatcher("/api/v1/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling {
                it.authenticationEntryPoint(jsonEntryPoint(mapper))
                    .accessDeniedHandler(jsonDeniedHandler(mapper))
            }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/sessions", "/api/v1/auth/credential-updates").permitAll()
                    .anyRequest().hasRole("STUDENT")
            }
            .addFilterAfter(
                PocketTokenAuthenticationFilter(sessions, accounts, mapper),
                org.springframework.security.web.access.ExceptionTranslationFilter::class.java,
            )
        return http.build()
    }

    @Bean
    @Order(3)
    fun denyOtherRequestsSecurityFilterChain(http: HttpSecurity, mapper: ObjectMapper): SecurityFilterChain {
        http.csrf { it.disable() }
            .authorizeHttpRequests { it.anyRequest().denyAll() }
            .exceptionHandling {
                it.authenticationEntryPoint(jsonEntryPoint(mapper))
                    .accessDeniedHandler(jsonDeniedHandler(mapper))
            }
        return http.build()
    }

    @Bean
    fun jsonEntryPoint(mapper: ObjectMapper): AuthenticationEntryPoint =
        AuthenticationEntryPoint { request, response, _ ->
            writeError(mapper, request, response, ApiErrorCode.UNAUTHENTICATED)
        }

    @Bean
    fun jsonDeniedHandler(mapper: ObjectMapper): AccessDeniedHandler =
        AccessDeniedHandler { request, response, _ ->
            writeError(mapper, request, response, ApiErrorCode.FORBIDDEN)
        }
}

private fun writeError(
    mapper: ObjectMapper,
    request: HttpServletRequest,
    response: HttpServletResponse,
    code: ApiErrorCode,
) {
    val requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) as? UUID ?: UUID.randomUUID()
    response.status = code.httpStatus
    response.contentType = MediaType.APPLICATION_JSON_VALUE
    response.characterEncoding = Charsets.UTF_8.name()
    mapper.writeValue(response.outputStream, ErrorResponse(code.name, requestId))
}

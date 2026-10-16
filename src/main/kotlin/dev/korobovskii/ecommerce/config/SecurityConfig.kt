package dev.korobovskii.ecommerce.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.HeadersDsl
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.DelegatingPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.header.writers.CrossOriginEmbedderPolicyHeaderWriter
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.security.web.savedrequest.NullRequestCache
import org.springframework.web.filter.OncePerRequestFilter
import java.text.Normalizer
import java.util.concurrent.Semaphore

@Configuration
class SecurityConfig {

    @Bean
    @Order(1)
    fun apiDocsFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            securityMatcher("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**")
            authorizeHttpRequests {
                authorize(anyRequest, permitAll)
            }
            headers {
                contentSecurityPolicy {
                    policyDirectives = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; " +
                        "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"
                }
                hardening()
            }
        }
        return http.build()
    }

    @Bean
    @Order(2)
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize("/api/admin/**", hasRole("ADMIN"))
                authorize("/api/me/**", authenticated)
                authorize(anyRequest, permitAll)
            }
            requestCache {
                requestCache = NullRequestCache()
            }
            formLogin {
                loginPage = "/login"
                loginProcessingUrl = "/api/auth/login"
                authenticationSuccessHandler = AuthenticationSuccessHandler { _, response, _ ->
                    response.status = HttpServletResponse.SC_NO_CONTENT
                }
                authenticationFailureHandler = AuthenticationFailureHandler { _, response, _ ->
                    response.status = HttpServletResponse.SC_UNAUTHORIZED
                }
                permitAll()
            }
            logout {
                logoutUrl = "/api/auth/logout"
                logoutSuccessHandler = HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)
            }
            exceptionHandling {
                authenticationEntryPoint = HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)
            }
            csrf {
                csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse().apply {
                    setCookieCustomizer { it.sameSite("Lax") }
                }
                csrfTokenRequestHandler = CsrfTokenRequestAttributeHandler()
            }
            addFilterAfter<CsrfFilter>(CsrfCookieFilter())
            headers {
                contentSecurityPolicy {
                    policyDirectives = "default-src 'self'; img-src 'self' data:; object-src 'none'; " +
                        "base-uri 'self'; form-action 'self'; frame-ancestors 'none'"
                }
                hardening()
            }
        }
        return http.build()
    }

    @Bean
    fun passwordEncoder(properties: AppProperties): PasswordEncoder {
        val argon2 = properties.security.argon2
        val encoders = mapOf<String, PasswordEncoder>(
            "argon2" to Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, argon2.parallelism, argon2.memoryKib, argon2.iterations),
            "bcrypt" to BCryptPasswordEncoder(),
        )
        val normalizing = NormalizingPasswordEncoder(DelegatingPasswordEncoder("argon2", encoders))
        return ConcurrencyLimitedPasswordEncoder(normalizing, properties.security.maxConcurrentHashes)
    }

    private companion object {
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
    }
}

private fun HeadersDsl.hardening() {
    referrerPolicy { policy = ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN }
    permissionsPolicy { policy = "camera=(), microphone=(), geolocation=(), payment=(), usb=()" }
    crossOriginOpenerPolicy { policy = CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN }
    crossOriginEmbedderPolicy { policy = CrossOriginEmbedderPolicyHeaderWriter.CrossOriginEmbedderPolicy.REQUIRE_CORP }
    crossOriginResourcePolicy { policy = CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy.SAME_ORIGIN }
}

class NormalizingPasswordEncoder(private val delegate: PasswordEncoder) : PasswordEncoder {

    override fun encode(rawPassword: CharSequence?): String? = delegate.encode(rawPassword?.let(::normalize))

    override fun matches(rawPassword: CharSequence?, encodedPassword: String?): Boolean =
        delegate.matches(rawPassword?.let(::normalize), encodedPassword)

    override fun upgradeEncoding(encodedPassword: String?): Boolean = delegate.upgradeEncoding(encodedPassword)

    private fun normalize(value: CharSequence): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
}

class ConcurrencyLimitedPasswordEncoder(private val delegate: PasswordEncoder, maxConcurrent: Int) : PasswordEncoder {

    private val permits = Semaphore(maxConcurrent, true)

    override fun encode(rawPassword: CharSequence?): String? = limited { delegate.encode(rawPassword) }

    override fun matches(rawPassword: CharSequence?, encodedPassword: String?): Boolean =
        limited { delegate.matches(rawPassword, encodedPassword) }

    override fun upgradeEncoding(encodedPassword: String?): Boolean = delegate.upgradeEncoding(encodedPassword)

    private fun <T> limited(hash: () -> T): T {
        permits.acquire()
        try {
            return hash()
        } finally {
            permits.release()
        }
    }
}

class CsrfCookieFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val token = request.getAttribute(CsrfToken::class.java.name) as? CsrfToken
        token?.token
        filterChain.doFilter(request, response)
    }
}

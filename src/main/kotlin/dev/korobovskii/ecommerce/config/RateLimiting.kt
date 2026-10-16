package dev.korobovskii.ecommerce.config

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.bucket4j.Bucket
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.concurrent.TimeUnit

class RateLimiter(maxKeys: Long, idleExpiry: Duration) {

    private val buckets: Cache<String, Bucket> = Caffeine.newBuilder()
        .maximumSize(maxKeys)
        .expireAfterAccess(idleExpiry)
        .build()

    fun secondsToWait(rule: String, key: String, limit: AppProperties.Limit): Long? {
        val bucket = buckets.get("$rule:$key") {
            Bucket.builder().addLimit { it.capacity(limit.requests).refillGreedy(limit.requests, limit.per) }.build()
        }
        val probe = bucket.tryConsumeAndReturnRemaining(1)
        return if (probe.isConsumed) null else TimeUnit.NANOSECONDS.toSeconds(probe.nanosToWaitForRefill) + 1
    }
}

class RateLimitFilter(
    private val limiter: RateLimiter,
    private val properties: AppProperties,
    private val json: JsonMapper,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !properties.rateLimit.enabled

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val (rule, limit) = ruleFor(request)
        val wait = limiter.secondsToWait(rule, request.remoteAddr, limit)
        if (wait == null) {
            filterChain.doFilter(request, response)
            return
        }
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again in $wait seconds.")
        problem.setProperty("code", "RATE_LIMITED")
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.setHeader(HttpHeaders.RETRY_AFTER, wait.toString())
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.writer.write(json.writeValueAsString(problem))
    }

    private fun ruleFor(request: HttpServletRequest): Pair<String, AppProperties.Limit> {
        val limits = properties.rateLimit
        if (request.method != "POST") return "api" to limits.api
        return when (request.servletPath + request.pathInfo.orEmpty()) {
            "/api/auth/login" -> "login" to limits.login
            "/api/auth/register" -> "register" to limits.register
            "/api/orders" -> "orders" to limits.orders
            "/api/admin/imports" -> "imports" to limits.imports
            else -> "api" to limits.api
        }
    }
}

@Configuration
class RateLimitConfig {

    @Bean
    fun rateLimitFilter(properties: AppProperties, json: JsonMapper): FilterRegistrationBean<RateLimitFilter> {
        val limiter = RateLimiter(properties.rateLimit.maxTrackedAddresses, properties.rateLimit.idleExpiry)
        return FilterRegistrationBean(RateLimitFilter(limiter, properties, json)).apply {
            order = BEFORE_SECURITY
            addUrlPatterns("/api/*")
        }
    }

    private companion object {
        const val BEFORE_SECURITY = -101
    }
}

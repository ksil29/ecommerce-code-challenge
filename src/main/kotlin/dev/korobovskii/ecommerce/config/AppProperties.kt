package dev.korobovskii.ecommerce.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

private const val DEFAULT_LOCK_MINUTES = 15L
private const val DEFAULT_PAYMENT_TIMEOUT_MINUTES = 15L
private const val DEFAULT_LOGIN_REQUESTS = 10L
private const val DEFAULT_REGISTER_REQUESTS = 5L
private const val DEFAULT_REGISTER_MINUTES = 10L
private const val DEFAULT_ORDER_REQUESTS = 30L
private const val DEFAULT_IMPORT_REQUESTS = 10L
private const val DEFAULT_API_REQUESTS = 600L
private const val DEFAULT_CONCURRENT_HASHES = 4
private const val DEFAULT_MAX_QUANTITY = 99
private const val DEFAULT_RECONCILE_BATCH = 100
private const val DEFAULT_HISTORY_SIZE = 50
private const val DEFAULT_IDEMPOTENCY_HOURS = 24L
private const val DEFAULT_TRACKED_ADDRESSES = 100_000L
private const val DEFAULT_IDLE_MINUTES = 30L
private const val DEFAULT_MIN_PASSWORD = 12
private const val DEFAULT_MAX_PASSWORD = 128
private const val LOWEST_MIN_PASSWORD = 8
private const val DEFAULT_ARGON2_MEMORY_KIB = 19_456
private const val DEFAULT_ARGON2_ITERATIONS = 2
private const val LOWEST_ARGON2_MEMORY_KIB = 7_168
private const val LOWEST_ARGON2_WORK = 35_840L

@ConfigurationProperties("app")
data class AppProperties(
    val admin: Admin = Admin(),
    val security: Security = Security(),
    val rateLimit: RateLimit = RateLimit(),
    val csvImport: CsvImport = CsvImport(),
    val orders: Orders = Orders(),
    val sampleData: SampleData = SampleData(),
) {
    data class Admin(val username: String = "admin", val password: String = "", val allowWeakPassword: Boolean = false)

    data class Security(
        val maxFailedLogins: Int = 5,
        val lockDuration: Duration = Duration.ofMinutes(DEFAULT_LOCK_MINUTES),
        val maxConcurrentHashes: Int = DEFAULT_CONCURRENT_HASHES,
        val password: Password = Password(),
        val argon2: Argon2 = Argon2(),
    )

    data class Password(val minLength: Int = DEFAULT_MIN_PASSWORD, val maxLength: Int = DEFAULT_MAX_PASSWORD) {
        init {
            require(minLength >= LOWEST_MIN_PASSWORD) {
                "app.security.password.min-length must be at least $LOWEST_MIN_PASSWORD (NIST SP 800-63B)"
            }
            require(maxLength >= minLength) { "app.security.password.max-length must not be below min-length" }
        }
    }

    data class Argon2(
        val memoryKib: Int = DEFAULT_ARGON2_MEMORY_KIB,
        val iterations: Int = DEFAULT_ARGON2_ITERATIONS,
        val parallelism: Int = 1,
    ) {
        init {
            val work = memoryKib.toLong() * iterations
            require(memoryKib >= LOWEST_ARGON2_MEMORY_KIB && work >= LOWEST_ARGON2_WORK && parallelism >= 1) {
                "app.security.argon2 must not be weaker than OWASP's lightest setting, 7168 KiB with 5 iterations"
            }
        }
    }

    data class Limit(val requests: Long, val per: Duration)

    data class RateLimit(
        val enabled: Boolean = true,
        val login: Limit = Limit(DEFAULT_LOGIN_REQUESTS, Duration.ofMinutes(1)),
        val register: Limit = Limit(DEFAULT_REGISTER_REQUESTS, Duration.ofMinutes(DEFAULT_REGISTER_MINUTES)),
        val orders: Limit = Limit(DEFAULT_ORDER_REQUESTS, Duration.ofMinutes(1)),
        val imports: Limit = Limit(DEFAULT_IMPORT_REQUESTS, Duration.ofMinutes(1)),
        val api: Limit = Limit(DEFAULT_API_REQUESTS, Duration.ofMinutes(1)),
        val maxTrackedAddresses: Long = DEFAULT_TRACKED_ADDRESSES,
        val idleExpiry: Duration = Duration.ofMinutes(DEFAULT_IDLE_MINUTES),
    )

    data class CsvImport(val maxRows: Int = 10_000)

    data class Orders(
        val maxQuantity: Int = DEFAULT_MAX_QUANTITY,
        val paymentTimeout: Duration = Duration.ofMinutes(DEFAULT_PAYMENT_TIMEOUT_MINUTES),
        val reconcileInterval: Duration = Duration.ofMinutes(1),
        val reconcileBatchSize: Int = DEFAULT_RECONCILE_BATCH,
        val idempotencyKeyTtl: Duration = Duration.ofHours(DEFAULT_IDEMPOTENCY_HOURS),
        val historySize: Int = DEFAULT_HISTORY_SIZE,
    )

    data class SampleData(
        val enabled: Boolean = false,
        val location: String = "classpath:sample/products.csv",
    )
}

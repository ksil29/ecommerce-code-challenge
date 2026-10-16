package dev.korobovskii.ecommerce.config

import org.junit.jupiter.api.Test
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class ConcurrencyLimitedPasswordEncoderTest {

    private val running = AtomicInteger()
    private val peak = AtomicInteger()
    private val release = CountDownLatch(1)

    private val slowEncoder = object : PasswordEncoder {
        override fun encode(rawPassword: CharSequence?): String {
            peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
            release.await()
            running.decrementAndGet()
            return "hash"
        }

        override fun matches(rawPassword: CharSequence?, encodedPassword: String?): Boolean = encode(rawPassword) == encodedPassword
    }

    @Test
    fun `no more than the allowed number of hashes run at once, and the rest wait their turn`() {
        val encoder = ConcurrencyLimitedPasswordEncoder(slowEncoder, 3)

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val results = (1..20).map { executor.submit<Boolean> { encoder.matches("secret", "hash") } }
            awaitRunning(3)
            Thread.sleep(200)
            assertEquals(3, running.get())

            release.countDown()
            assertEquals(20, results.count { it.get() })
        }
        assertEquals(3, peak.get())
    }

    private fun awaitRunning(expected: Int) {
        val deadline = Instant.now().plusSeconds(5)
        while (running.get() < expected) {
            check(Instant.now().isBefore(deadline)) { "Only ${running.get()} hashes started" }
            Thread.sleep(5)
        }
    }
}

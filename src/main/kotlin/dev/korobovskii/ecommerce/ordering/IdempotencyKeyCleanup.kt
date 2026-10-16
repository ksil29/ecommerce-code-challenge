package dev.korobovskii.ecommerce.ordering

import dev.korobovskii.ecommerce.config.AppProperties
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

@Component
class IdempotencyKeyCleanup(
    private val orders: OrderRepository,
    private val properties: AppProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    @SchedulerLock(name = "idempotency-key-cleanup", lockAtMostFor = "PT10M")
    fun run() {
        forget(clock.instant())
    }

    fun forget(now: Instant): Int {
        val ttl = properties.orders.idempotencyKeyTtl
        val forgotten = orders.forgetIdempotencyKeys(now.minus(ttl))
        if (forgotten > 0) log.info("Forgot the idempotency keys of {} orders older than {}", forgotten, ttl)
        return forgotten
    }
}

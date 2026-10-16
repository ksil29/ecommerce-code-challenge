package dev.korobovskii.ecommerce.ordering

import dev.korobovskii.ecommerce.config.AppProperties
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

@Component
class PaymentReconciler(
    private val orders: OrderRepository,
    private val gateway: PaymentGateway,
    private val settlement: PaymentSettlement,
    private val properties: AppProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${app.orders.reconcile-interval:PT1M}",
        initialDelayString = "\${app.orders.reconcile-interval:PT1M}",
    )
    @SchedulerLock(name = "payment-reconciliation", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    fun run() {
        reconcile(clock.instant())
    }

    @Suppress("TooGenericExceptionCaught")
    fun reconcile(now: Instant): Int {
        val cutoff = now.minus(properties.orders.paymentTimeout)
        var resolved = 0
        for (order in orders.pendingBefore(cutoff, properties.orders.reconcileBatchSize)) {
            try {
                val result = gateway.find(order.publicId)
                if (settlement.record(order, result)) {
                    resolved++
                    log.info("Order {} resolved by reconciliation: {}", order.publicId, result ?: "no charge found")
                }
            } catch (e: RuntimeException) {
                log.warn("Could not reconcile order {}: {}", order.publicId, e.message)
            }
        }
        return resolved
    }

}

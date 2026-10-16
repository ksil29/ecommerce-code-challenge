package dev.korobovskii.ecommerce.audit

import dev.korobovskii.ecommerce.ordering.OrderRepository
import dev.korobovskii.ecommerce.ordering.OrderStatus
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class ShopMetrics(private val registry: MeterRegistry, orders: OrderRepository) {

    init {
        Gauge.builder("shop.orders.pending") { orders.countPending().toDouble() }
            .description("Orders waiting for a payment answer")
            .register(registry)
    }

    fun orderStatus(status: OrderStatus) {
        registry.counter("shop.orders", "status", status.name.lowercase()).increment()
    }

    fun checkoutRejected(reason: String) {
        registry.counter("shop.checkout.rejected", "reason", reason).increment()
    }

    fun importRows(result: String, count: Int) {
        if (count > 0) registry.counter("shop.import.rows", "result", result).increment(count.toDouble())
    }
}

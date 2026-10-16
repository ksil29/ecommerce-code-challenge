package dev.korobovskii.ecommerce.ordering

import dev.korobovskii.ecommerce.audit.AuditLog
import dev.korobovskii.ecommerce.audit.ShopMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class PaymentSettlement(
    private val orders: OrderRepository,
    private val audit: AuditLog,
    private val metrics: ShopMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun record(order: Order, result: ChargeResult?): Boolean {
        val changed = when (result) {
            is ChargeResult.Approved -> paid(order, result.reference)
            is ChargeResult.Declined -> cancel(order, OrderStatus.PAYMENT_FAILED, result.reason)
            null -> cancel(order, OrderStatus.EXPIRED, "Payment was not confirmed in time")
        }
        if (!changed && result is ChargeResult.Approved && !paidWith(order, result.reference)) {
            log.error("Order {} was charged ({}) after it left PENDING_PAYMENT; it needs a refund", order.publicId, result.reference)
            audit.record("REFUND_NEEDED", "order", order.publicId.toString(), mapOf("reference" to result.reference))
        }
        return changed
    }

    private fun paidWith(order: Order, reference: String): Boolean =
        orders.findById(order.id)?.let { it.status == OrderStatus.PAID && it.paymentReference == reference } == true

    private fun paid(order: Order, reference: String): Boolean {
        val changed = orders.transition(order.id, OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, paymentReference = reference)
        if (changed) {
            val shortfalls = orders.sell(order.id)
            metrics.orderStatus(OrderStatus.PAID)
            audit.record("ORDER_PAID", "order", order.publicId.toString(), mapOf("reference" to reference))
            if (shortfalls.isNotEmpty()) {
                log.error("Order {} was paid for units that are no longer on hand: {}", order.publicId, shortfalls)
                audit.record(
                    "STOCK_SHORTFALL", "order", order.publicId.toString(),
                    mapOf("missing" to shortfalls.map { mapOf("sku" to it.sku, "units" to it.units) }),
                )
            }
        }
        return changed
    }

    private fun cancel(order: Order, status: OrderStatus, reason: String): Boolean {
        val changed = orders.transition(order.id, OrderStatus.PENDING_PAYMENT, status, failureReason = reason)
        if (changed) {
            orders.release(order.id)
            metrics.orderStatus(status)
            audit.record("ORDER_${status.name}", "order", order.publicId.toString(), mapOf("reason" to reason))
        }
        return changed
    }
}

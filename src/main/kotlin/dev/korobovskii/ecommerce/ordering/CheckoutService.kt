package dev.korobovskii.ecommerce.ordering

import dev.korobovskii.ecommerce.audit.AuditLog
import dev.korobovskii.ecommerce.audit.ShopMetrics
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.UUID

private val MAX_ORDER_TOTAL = BigDecimal("9999999999999999.99")

@Service
class CheckoutService(
    private val orders: OrderRepository,
    private val gateway: PaymentGateway,
    private val settlement: PaymentSettlement,
    private val audit: AuditLog,
    private val metrics: ShopMetrics,
    private val observations: ObservationRegistry,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)

    fun placeOrder(request: CheckoutRequest): Order {
        val fingerprint = request.fingerprint()
        orders.findByIdempotencyKey(request.idempotencyKey)?.let { return replay(it, fingerprint) }
        if (request.items.isEmpty()) throw EmptyCartException()
        val orderId = reserve(request, fingerprint) ?: return replay(orders.findByIdempotencyKey(request.idempotencyKey)!!, fingerprint)
        val order = orders.findById(orderId)!!
        val result = try {
            charge(order, request.cardNumber)
        } catch (e: PaymentUnavailableException) {
            log.warn("Payment for order {} is not confirmed yet: {}", order.publicId, e.message)
            metrics.orderStatus(OrderStatus.PENDING_PAYMENT)
            return order
        }
        settlement.record(order, result)
        return orders.findById(orderId)!!
    }

    private fun replay(order: Order, fingerprint: String): Order {
        if (order.requestHash == fingerprint) return order
        metrics.checkoutRejected("idempotency_key_reused")
        throw IdempotencyKeyReusedException()
    }

    private fun reserve(request: CheckoutRequest, fingerprint: String): Long? =
        try {
            transaction.execute { reserveAndCreate(request, fingerprint) }
        } catch (e: DuplicateKeyException) {
            if (orders.findByIdempotencyKey(request.idempotencyKey) == null) throw e
            null
        } catch (e: OutOfStockException) {
            metrics.checkoutRejected("out_of_stock")
            throw e
        } catch (e: PriceChangedException) {
            metrics.checkoutRejected("price_changed")
            throw e
        }

    private fun charge(order: Order, cardNumber: String): ChargeResult {
        val observation = Observation.createNotStarted("shop.payment", observations)
            .lowCardinalityKeyValue("provider", "fake")
            .highCardinalityKeyValue("order", order.publicId.toString())
            .start()
        try {
            val result = gateway.charge(ChargeRequest(order.publicId, order.total, order.currency, cardNumber))
            observation.lowCardinalityKeyValue("outcome", if (result is ChargeResult.Approved) "approved" else "declined")
            return result
        } catch (e: PaymentUnavailableException) {
            observation.lowCardinalityKeyValue("outcome", "unavailable")
            observation.error(e)
            throw e
        } finally {
            observation.stop()
        }
    }

    private fun reserveAndCreate(request: CheckoutRequest, fingerprint: String): Long? {
        orders.lockIdempotencyKey(request.idempotencyKey)
        if (orders.findByIdempotencyKey(request.idempotencyKey) != null) return null
        val lines = request.items
            .groupBy { it.productId }
            .map { (productId, items) -> CheckoutItem(productId, items.sumOf { it.quantity }) }
            .sortedBy { it.productId }
            .mapIndexed { index, item ->
                val reserved = orders.reserve(item.productId, item.quantity) ?: throw OutOfStockException(item.productId)
                OrderLine(index + 1, reserved.productId, reserved.sku, reserved.name, reserved.price, item.quantity)
            }
        val total = lines.fold(BigDecimal.ZERO) { sum, line -> sum + line.lineTotal }
        checkTotal(total, request.expectedTotal)
        val publicId = UUID.randomUUID()
        val orderId = orders.create(
            publicId = publicId,
            idempotencyKey = request.idempotencyKey,
            requestHash = fingerprint,
            customerName = request.customerName,
            customerEmail = request.customerEmail,
            total = total,
            cardLast4 = CardNumbers.lastFour(request.cardNumber),
            accountId = request.accountId,
            lines = lines,
        )
        audit.record(
            "ORDER_PLACED", "order", publicId.toString(),
            mapOf("total" to total.toPlainString(), "lines" to lines.size, "accountId" to request.accountId),
        )
        return orderId
    }

    private fun checkTotal(total: BigDecimal, expected: BigDecimal) {
        if (total > MAX_ORDER_TOTAL) throw OrderTooLargeException()
        if (total.compareTo(expected) != 0) throw PriceChangedException(total)
    }
}

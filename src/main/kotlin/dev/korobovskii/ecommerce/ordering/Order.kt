package dev.korobovskii.ecommerce.ordering

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

enum class OrderStatus(val label: String) {
    PENDING_PAYMENT("Payment pending"),
    PAID("Paid"),
    PAYMENT_FAILED("Payment failed"),
    EXPIRED("Expired"),
}

data class OrderLine(
    val lineNo: Int,
    val productId: Long,
    val sku: String,
    val productName: String,
    val unitPrice: BigDecimal,
    val quantity: Int,
) {
    val lineTotal: BigDecimal
        get() = unitPrice.multiply(BigDecimal(quantity))
}

data class Order(
    val id: Long,
    val publicId: UUID,
    val status: OrderStatus,
    val customerName: String,
    val customerEmail: String,
    val total: BigDecimal,
    val currency: String,
    val cardLast4: String?,
    val paymentReference: String?,
    val failureReason: String?,
    val createdAt: Instant,
    val accountId: Long? = null,
    val requestHash: String? = null,
    val lines: List<OrderLine> = emptyList(),
)

data class CheckoutItem(val productId: Long, val quantity: Int)

data class CheckoutRequest(
    val idempotencyKey: UUID,
    val items: List<CheckoutItem>,
    val expectedTotal: BigDecimal,
    val customerName: String,
    val customerEmail: String,
    val cardNumber: String,
    val accountId: Long? = null,
) {
    fun fingerprint(): String {
        val lines = items.groupBy { it.productId }
            .map { (productId, sameProduct) -> "$productId:${sameProduct.sumOf { it.quantity }}" }
            .sorted()
        val purchase = listOf(accountId?.toString() ?: "guest", lines.joinToString(","), expectedTotal.stripTrailingZeros().toPlainString())
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(purchase.joinToString("|").toByteArray()))
    }
}

sealed class OrderRefusedException(val code: String, message: String) : RuntimeException(message)

class EmptyCartException : OrderRefusedException("EMPTY_CART", "The cart is empty")

class OutOfStockException(val productId: Long) : RuntimeException("Not enough stock for product $productId")

class IdempotencyKeyReusedException :
    RuntimeException("This Idempotency-Key was already used for a different purchase. Start a new checkout.")

class PriceChangedException(val newTotal: BigDecimal) : RuntimeException("The order total changed to $newTotal")

class OrderTooLargeException :
    OrderRefusedException("ORDER_TOO_LARGE", "The order total is above the limit. Split it into smaller orders.")

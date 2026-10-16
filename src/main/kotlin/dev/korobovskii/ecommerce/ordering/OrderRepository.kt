package dev.korobovskii.ecommerce.ordering

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class ReservedItem(val productId: Long, val sku: String, val name: String, val price: BigDecimal)

data class Shortfall(val sku: String, val units: Int)

@Repository
class OrderRepository(private val jdbc: JdbcClient) {

    private val orderMapper = RowMapper { rs, _ ->
        Order(
            id = rs.getLong("id"),
            publicId = rs.getObject("public_id", UUID::class.java),
            status = OrderStatus.valueOf(rs.getString("status")),
            customerName = rs.getString("customer_name"),
            customerEmail = rs.getString("customer_email"),
            total = rs.getBigDecimal("total"),
            currency = rs.getString("currency"),
            cardLast4 = rs.getString("card_last4"),
            paymentReference = rs.getString("payment_reference"),
            failureReason = rs.getString("failure_reason"),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            accountId = rs.getObject("account_id", Long::class.javaObjectType),
            requestHash = rs.getString("request_hash"),
        )
    }

    private val reservedMapper = RowMapper { rs, _ ->
        ReservedItem(rs.getLong("id"), rs.getString("sku"), rs.getString("name"), rs.getBigDecimal("price"))
    }

    private val lineMapper = RowMapper { rs, _ ->
        OrderLine(
            lineNo = rs.getInt("line_no"),
            productId = rs.getLong("product_id"),
            sku = rs.getString("sku"),
            productName = rs.getString("product_name"),
            unitPrice = rs.getBigDecimal("unit_price"),
            quantity = rs.getInt("quantity"),
        )
    }

    fun reserve(productId: Long, quantity: Int): ReservedItem? =
        jdbc.sql(
            """
            UPDATE product SET reserved = reserved + :quantity, updated_at = now()
            WHERE id = :id AND archived = FALSE AND stock - reserved >= :quantity
            RETURNING id, sku, name, price
            """.trimIndent(),
        ).param("id", productId).param("quantity", quantity)
            .query(reservedMapper)
            .optional()
            .orElse(null)

    fun lockIdempotencyKey(key: UUID) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)")
            .param("key", key.mostSignificantBits xor key.leastSignificantBits)
            .query()
            .listOfRows()
    }

    fun release(orderId: Long) {
        lockProducts(orderId)
        jdbc.sql(
            """
            UPDATE product p SET reserved = p.reserved - l.quantity, updated_at = now()
            FROM order_line l
            WHERE l.order_id = :orderId AND p.id = l.product_id
            """.trimIndent(),
        ).param("orderId", orderId).update()
    }

    fun sell(orderId: Long): List<Shortfall> {
        val shortfalls = lockProducts(orderId).filter { it.onHand < it.quantity }.map { Shortfall(it.sku, it.quantity - it.onHand) }
        jdbc.sql(
            """
            UPDATE product p SET stock = GREATEST(p.stock - l.quantity, 0), reserved = p.reserved - l.quantity,
                                 version = p.version + 1, updated_at = now()
            FROM order_line l
            WHERE l.order_id = :orderId AND p.id = l.product_id
            """.trimIndent(),
        ).param("orderId", orderId).update()
        return shortfalls
    }

    private fun lockProducts(orderId: Long): List<HeldLine> =
        jdbc.sql(
            """
            SELECT p.sku, p.stock, l.quantity FROM product p JOIN order_line l ON l.product_id = p.id
            WHERE l.order_id = :orderId ORDER BY p.id FOR UPDATE OF p
            """.trimIndent(),
        ).param("orderId", orderId)
            .query { rs, _ -> HeldLine(rs.getString("sku"), rs.getInt("stock"), rs.getInt("quantity")) }
            .list()

    private data class HeldLine(val sku: String, val onHand: Int, val quantity: Int)

    fun create(
        publicId: UUID,
        idempotencyKey: UUID,
        requestHash: String,
        customerName: String,
        customerEmail: String,
        total: BigDecimal,
        cardLast4: String,
        accountId: Long?,
        lines: List<OrderLine>,
    ): Long {
        val orderId = jdbc.sql(
            """
            INSERT INTO customer_order
                (public_id, idempotency_key, request_hash, status, customer_name, customer_email, total, currency, card_last4, account_id)
            VALUES
                (:publicId, :idempotencyKey, :requestHash, 'PENDING_PAYMENT', :customerName, :customerEmail, :total, 'USD', :cardLast4,
                 :accountId)
            RETURNING id
            """.trimIndent(),
        ).param("publicId", publicId)
            .param("idempotencyKey", idempotencyKey)
            .param("requestHash", requestHash)
            .param("customerName", customerName)
            .param("customerEmail", customerEmail)
            .param("total", total)
            .param("cardLast4", cardLast4)
            .param("accountId", accountId, Types.BIGINT)
            .query(Long::class.javaObjectType)
            .single()
        lines.forEach { line ->
            jdbc.sql(
                """
                INSERT INTO order_line (order_id, line_no, product_id, sku, product_name, unit_price, quantity)
                VALUES (:orderId, :lineNo, :productId, :sku, :productName, :unitPrice, :quantity)
                """.trimIndent(),
            ).param("orderId", orderId)
                .param("lineNo", line.lineNo)
                .param("productId", line.productId)
                .param("sku", line.sku)
                .param("productName", line.productName)
                .param("unitPrice", line.unitPrice)
                .param("quantity", line.quantity)
                .update()
        }
        return orderId
    }

    fun transition(
        orderId: Long,
        from: OrderStatus,
        to: OrderStatus,
        paymentReference: String? = null,
        failureReason: String? = null,
    ): Boolean =
        jdbc.sql(
            """
            UPDATE customer_order
            SET status = :to,
                payment_reference = coalesce(:paymentReference, payment_reference),
                failure_reason = coalesce(:failureReason, failure_reason),
                updated_at = now()
            WHERE id = :id AND status = :from
            """.trimIndent(),
        ).param("id", orderId)
            .param("from", from.name)
            .param("to", to.name)
            .param("paymentReference", paymentReference, Types.VARCHAR)
            .param("failureReason", failureReason, Types.VARCHAR)
            .update() == 1

    fun findById(id: Long): Order? = findOne("o.id = :value", id)

    fun findByPublicId(publicId: UUID): Order? = findOne("o.public_id = :value", publicId)

    fun findByIdempotencyKey(key: UUID): Order? = findOne("o.idempotency_key = :value", key)

    fun forgetIdempotencyKeys(createdBefore: Instant): Int =
        jdbc.sql(
            """
            UPDATE customer_order SET idempotency_key = NULL, request_hash = NULL
            WHERE idempotency_key IS NOT NULL AND created_at < :cutoff
            """.trimIndent(),
        ).param("cutoff", OffsetDateTime.ofInstant(createdBefore, ZoneOffset.UTC)).update()

    fun pendingBefore(cutoff: Instant, limit: Int): List<Order> =
        jdbc.sql(
            """
            SELECT * FROM customer_order o
            WHERE o.status = 'PENDING_PAYMENT' AND o.created_at < :cutoff
            ORDER BY o.created_at
            LIMIT :limit
            """.trimIndent(),
        ).param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
            .param("limit", limit)
            .query(orderMapper)
            .list()

    fun forAccount(accountId: Long, limit: Int): List<Order> =
        jdbc.sql(
            "SELECT * FROM customer_order o WHERE o.account_id = :accountId ORDER BY o.created_at DESC, o.id DESC LIMIT :limit",
        )
            .param("accountId", accountId)
            .param("limit", limit)
            .query(orderMapper)
            .list()

    fun countPending(): Long =
        jdbc.sql("SELECT count(*) FROM customer_order WHERE status = 'PENDING_PAYMENT'").query(Long::class.javaObjectType).single()

    fun recent(limit: Int): List<Order> =
        jdbc.sql("SELECT * FROM customer_order o ORDER BY o.created_at DESC, o.id DESC LIMIT :limit")
            .param("limit", limit)
            .query(orderMapper)
            .list()

    private fun findOne(condition: String, value: Any): Order? {
        val order = jdbc.sql("SELECT * FROM customer_order o WHERE $condition")
            .param("value", value)
            .query(orderMapper)
            .optional()
            .orElse(null) ?: return null
        val lines = jdbc.sql("SELECT * FROM order_line WHERE order_id = :orderId ORDER BY line_no")
            .param("orderId", order.id)
            .query(lineMapper)
            .list()
        return order.copy(lines = lines)
    }
}

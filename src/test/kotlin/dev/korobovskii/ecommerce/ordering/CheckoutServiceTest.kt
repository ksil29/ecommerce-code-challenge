package dev.korobovskii.ecommerce.ordering

import dev.korobovskii.ecommerce.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CheckoutServiceTest : IntegrationTest() {

    @Autowired
    lateinit var checkout: CheckoutService

    @Autowired
    lateinit var reconciler: PaymentReconciler

    @Autowired
    lateinit var gateway: PaymentGateway

    @Autowired
    lateinit var settlement: PaymentSettlement

    @Autowired
    lateinit var orders: OrderRepository

    @Autowired
    lateinit var keys: IdempotencyKeyCleanup

    private fun refundsNeeded() =
        jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'REFUND_NEEDED'").query(Int::class.javaObjectType).single()

    private fun orderCount() = jdbc.sql("SELECT count(*) FROM customer_order").query(Int::class.javaObjectType).single()

    private fun request(
        vararg items: CheckoutItem,
        expectedTotal: String,
        card: String = FakePaymentGateway.APPROVED_CARD,
        key: UUID = UUID.randomUUID(),
        accountId: Long? = null,
    ) = CheckoutRequest(key, items.toList(), BigDecimal(expectedTotal), "Ada Buyer", "ada@example.com", card, accountId)

    @Test
    fun `an order whose total does not fit the total column is refused, and nothing is held`() {
        val gold = createProduct(sku = "GOLD-1", price = "9999999999.99", stock = 1_000_000)
        val platinum = createProduct(sku = "PLAT-1", price = "9999999999.99", stock = 1_000_000)

        assertFailsWith<OrderTooLargeException> {
            checkout.placeOrder(request(CheckoutItem(gold, 1_000_000), CheckoutItem(platinum, 1_000_000), expectedTotal = "1"))
        }

        assertEquals(1_000_000, availableOf(gold))
        assertEquals(0, orderCount())
    }

    @Test
    fun `a key used again for a different purchase is refused, and nothing is taken`() {
        val pen = createProduct(price = "2.50", stock = 10)
        val key = UUID.randomUUID()
        checkout.placeOrder(request(CheckoutItem(pen, 1), expectedTotal = "2.50", key = key))

        assertFailsWith<IdempotencyKeyReusedException> {
            checkout.placeOrder(request(CheckoutItem(pen, 2), expectedTotal = "5.00", key = key))
        }

        assertEquals(9, stockOf(pen))
        assertEquals(1, orderCount())
    }

    @Test
    fun `a key is tied to the account, so another account cannot replay it`() {
        val pen = createProduct(price = "2.50", stock = 10)
        val admin = jdbc.sql("SELECT id FROM account WHERE username = 'admin'").query(Long::class.javaObjectType).single()
        val key = UUID.randomUUID()
        val first = checkout.placeOrder(request(CheckoutItem(pen, 1), expectedTotal = "2.50", key = key, accountId = admin))

        assertFailsWith<IdempotencyKeyReusedException> {
            checkout.placeOrder(request(CheckoutItem(pen, 1), expectedTotal = "2.50", key = key))
        }
        assertEquals(first.id, checkout.placeOrder(request(CheckoutItem(pen, 1), expectedTotal = "2.5", key = key, accountId = admin)).id)
        assertEquals(1, orderCount())
    }

    @Test
    fun `a key is forgotten after a day and can then start a new order`() {
        val pen = createProduct(price = "2.50", stock = 10)
        val key = UUID.randomUUID()
        val first = checkout.placeOrder(request(CheckoutItem(pen, 1), expectedTotal = "2.50", key = key))

        assertEquals(0, keys.forget(Instant.now()))
        assertEquals(1, keys.forget(Instant.now().plus(Duration.ofHours(25))))

        val second = checkout.placeOrder(request(CheckoutItem(pen, 2), expectedTotal = "5.00", key = key))
        assertTrue(first.id != second.id)
        assertEquals(7, stockOf(pen))
    }

    @Test
    fun `a paid order takes the stock and keeps a snapshot of the lines`() {
        val pen = createProduct(sku = "PEN-1", price = "2.50", stock = 10, name = "Pen")
        val pad = createProduct(sku = "PAD-1", price = "4.00", stock = 5, name = "Pad")

        val order = checkout.placeOrder(request(CheckoutItem(pen, 2), CheckoutItem(pad, 1), expectedTotal = "9.00"))

        assertEquals(OrderStatus.PAID, order.status)
        assertNotNull(order.paymentReference)
        assertEquals("4242", order.cardLast4)
        assertEquals(BigDecimal("9.00"), order.total)
        assertEquals(listOf("PEN-1" to 2, "PAD-1" to 1), order.lines.map { it.sku to it.quantity })
        assertEquals(8, stockOf(pen))
        assertEquals(4, stockOf(pad))

        jdbc.sql("UPDATE product SET name = name || ' (new)', price = price * 2").update()
        val lines = orders.findById(order.id)!!.lines
        assertEquals(listOf("Pen" to BigDecimal("2.50"), "Pad" to BigDecimal("4.00")), lines.map { it.productName to it.unitPrice })
    }

    @Test
    fun `two requests with one key for the last item both get the one order`() {
        repeat(10) { round ->
            val pen = createProduct(sku = "LAST-$round", price = "2.50", stock = 1)
            val key = UUID.randomUUID()

            val outcomes = concurrently(2) {
                runCatching { checkout.placeOrder(request(CheckoutItem(pen, 1), expectedTotal = "2.50", key = key)) }
            }

            assertTrue(outcomes.all { it.isSuccess }, outcomes.toString())
            assertEquals(1, outcomes.map { it.getOrThrow().id }.toSet().size)
            assertEquals(0, stockOf(pen))
        }
    }

    @Test
    fun `a charge that lands after the order expired is flagged for a refund, and the stock comes back once`() {
        val pen = createProduct(price = "2.50", stock = 10)
        val order = checkout.placeOrder(request(CheckoutItem(pen, 2), expectedTotal = "5.00", card = FakePaymentGateway.TIMEOUT_CARD))
        assertEquals(1, reconciler.reconcile(Instant.now().plus(Duration.ofHours(1))))

        assertFalse(settlement.record(order, ChargeResult.Approved("late-charge")))

        assertEquals(1, refundsNeeded())
        assertEquals(10, stockOf(pen))
        assertEquals(OrderStatus.EXPIRED, orders.findById(order.id)!!.status)
    }

    @Test
    fun `the same approved charge recorded twice is not a refund case`() {
        val pen = createProduct(price = "2.50", stock = 10)
        val order = checkout.placeOrder(request(CheckoutItem(pen, 1), expectedTotal = "2.50"))

        assertFalse(settlement.record(order, ChargeResult.Approved(order.paymentReference!!)))

        assertEquals(0, refundsNeeded())
        assertEquals(OrderStatus.PAID, orders.findById(order.id)!!.status)
    }

    @Test
    fun `a declined card gives the stock back`() {
        val pen = createProduct(price = "2.50", stock = 10)

        val order = checkout.placeOrder(request(CheckoutItem(pen, 3), expectedTotal = "7.50", card = FakePaymentGateway.DECLINED_CARD))

        assertEquals(OrderStatus.PAYMENT_FAILED, order.status)
        assertEquals("Card declined", order.failureReason)
        assertEquals(10, stockOf(pen))
    }

    @Test
    fun `repeating a request with the same key returns the same order and charges once`() {
        val pen = createProduct(price = "2.50", stock = 10)
        val key = UUID.randomUUID()

        val first = checkout.placeOrder(request(CheckoutItem(pen, 1), expectedTotal = "2.50", key = key))
        val second = checkout.placeOrder(request(CheckoutItem(pen, 1), expectedTotal = "2.50", key = key))

        assertEquals(first.id, second.id)
        assertEquals(9, stockOf(pen))
        assertEquals(1, jdbc.sql("SELECT count(*) FROM customer_order").query(Int::class.javaObjectType).single())
    }

    @Test
    fun `a changed price stops the order before anything is taken`() {
        val pen = createProduct(price = "2.50", stock = 10)

        val error = assertFailsWith<PriceChangedException> {
            checkout.placeOrder(request(CheckoutItem(pen, 2), expectedTotal = "4.00"))
        }

        assertEquals(BigDecimal("5.00"), error.newTotal)
        assertEquals(10, stockOf(pen))
    }

    @Test
    fun `one missing item cancels the whole order`() {
        val pen = createProduct(sku = "PEN-1", price = "2.50", stock = 10)
        val pad = createProduct(sku = "PAD-1", price = "4.00", stock = 1)

        assertFailsWith<OutOfStockException> {
            checkout.placeOrder(request(CheckoutItem(pen, 2), CheckoutItem(pad, 2), expectedTotal = "13.00"))
        }

        assertEquals(10, stockOf(pen))
        assertEquals(1, stockOf(pad))
    }

    @Test
    fun `concurrent buyers never take more than the stock`() {
        val pen = createProduct(price = "2.50", stock = 50)
        val outcomes = concurrently(200) {
            runCatching { checkout.placeOrder(request(CheckoutItem(pen, 1), expectedTotal = "2.50")) }
        }

        assertEquals(50, outcomes.count { it.getOrNull()?.status == OrderStatus.PAID })
        assertEquals(150, outcomes.count { it.exceptionOrNull() is OutOfStockException })
        assertEquals(0, stockOf(pen))
        assertEquals(50, jdbc.sql("SELECT count(*) FROM customer_order").query(Int::class.javaObjectType).single())
    }

    @Test
    fun `the same key sent twice at the same moment creates one order`() {
        val pen = createProduct(price = "2.50", stock = 100)
        repeat(10) {
            val key = UUID.randomUUID()
            val outcomes = concurrently(2) { checkout.placeOrder(request(CheckoutItem(pen, 1), expectedTotal = "2.50", key = key)) }
            assertEquals(1, outcomes.map { it.id }.toSet().size)
        }
        assertEquals(90, stockOf(pen))
        assertEquals(10, jdbc.sql("SELECT count(*) FROM customer_order").query(Int::class.javaObjectType).single())
    }

    @Test
    fun `insufficient funds is a decline`() {
        val pen = createProduct(price = "2.50", stock = 10)

        val order = checkout.placeOrder(request(CheckoutItem(pen, 1), expectedTotal = "2.50", card = FakePaymentGateway.NO_FUNDS_CARD))

        assertEquals(OrderStatus.PAYMENT_FAILED, order.status)
        assertEquals("Insufficient funds", order.failureReason)
        assertEquals(10, stockOf(pen))
    }

    @Test
    fun `a charge that landed while the order was pending is settled as paid`() {
        val pen = createProduct(price = "2.50", stock = 10)
        val order = checkout.placeOrder(request(CheckoutItem(pen, 2), expectedTotal = "5.00", card = FakePaymentGateway.TIMEOUT_CARD))
        gateway.charge(ChargeRequest(order.publicId, order.total, order.currency, FakePaymentGateway.APPROVED_CARD))

        assertEquals(1, reconciler.reconcile(Instant.now().plus(Duration.ofHours(1))))

        assertEquals(
            "PAID",
            jdbc.sql("SELECT status FROM customer_order WHERE id = :id").param("id", order.id).query(String::class.java).single(),
        )
        assertEquals(8, stockOf(pen))
    }

    private fun <T> concurrently(threads: Int, task: () -> T): List<T> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = (1..threads).map {
                pool.submit(
                    Callable {
                        start.await()
                        task()
                    },
                )
            }
            start.countDown()
            return futures.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `an unconfirmed payment expires and gives the stock back`() {
        val pen = createProduct(price = "2.50", stock = 10)

        val order = checkout.placeOrder(request(CheckoutItem(pen, 4), expectedTotal = "10.00", card = FakePaymentGateway.TIMEOUT_CARD))
        assertEquals(OrderStatus.PENDING_PAYMENT, order.status)
        assertEquals(10, stockOf(pen))
        assertEquals(6, availableOf(pen))

        assertEquals(0, reconciler.reconcile(Instant.now()))
        assertEquals(1, reconciler.reconcile(Instant.now().plus(Duration.ofHours(1))))
        assertEquals(0, reconciler.reconcile(Instant.now().plus(Duration.ofHours(1))))

        assertEquals(10, stockOf(pen))
        assertEquals(10, availableOf(pen))
        assertEquals(
            "EXPIRED",
            jdbc.sql("SELECT status FROM customer_order WHERE id = :id").param("id", order.id).query(String::class.java).single(),
        )
    }

    @Test
    fun `an archived product cannot be bought`() {
        val pen = createProduct(price = "2.50", stock = 10)
        productService.archive(pen, 0)

        assertFailsWith<OutOfStockException> {
            checkout.placeOrder(request(CheckoutItem(pen, 1), expectedTotal = "2.50"))
        }
    }
}

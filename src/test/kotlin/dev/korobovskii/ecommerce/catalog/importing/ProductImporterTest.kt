package dev.korobovskii.ecommerce.catalog.importing

import dev.korobovskii.ecommerce.IntegrationTest
import dev.korobovskii.ecommerce.catalog.ProductRepository
import dev.korobovskii.ecommerce.ordering.ChargeRequest
import dev.korobovskii.ecommerce.ordering.CheckoutItem
import dev.korobovskii.ecommerce.ordering.CheckoutRequest
import dev.korobovskii.ecommerce.ordering.CheckoutService
import dev.korobovskii.ecommerce.ordering.FakePaymentGateway
import dev.korobovskii.ecommerce.ordering.PaymentGateway
import dev.korobovskii.ecommerce.ordering.PaymentReconciler
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductImporterTest : IntegrationTest() {

    @Autowired
    lateinit var importer: ProductImporter

    @Autowired
    lateinit var products: ProductRepository

    @Autowired
    lateinit var checkout: CheckoutService

    @Autowired
    lateinit var reconciler: PaymentReconciler

    @Autowired
    lateinit var gateway: PaymentGateway

    private fun holdFourPens(pen: Long) = checkout.placeOrder(
        CheckoutRequest(
            UUID.randomUUID(), listOf(CheckoutItem(pen, 4)), BigDecimal("10.00"), "Ada", "ada@example.com",
            FakePaymentGateway.TIMEOUT_CARD,
        ),
    )

    private fun importPens(onHand: Int): ImportReport {
        val file = "name,sku,description,category,price,stock,weight_kg\nTest product,PEN-1,,Tests,2.50,$onHand,\n"
        return importer.import("stock.csv", file.toByteArray())
    }

    @Test
    fun `an import sets what is on hand and leaves the units held by unpaid orders alone`() {
        val pen = createProduct(sku = "PEN-1", price = "2.50", stock = 10)
        holdFourPens(pen)
        assertEquals(6, availableOf(pen))

        assertEquals(1, importPens(onHand = 10).unchanged)
        assertEquals(10, stockOf(pen))
        assertEquals(6, availableOf(pen))

        assertEquals(1, reconciler.reconcile(Instant.now().plus(Duration.ofHours(1))))
        assertEquals(10, stockOf(pen))
        assertEquals(10, availableOf(pen))
    }

    @Test
    fun `a file with fewer units than unpaid orders hold is flagged, and paying for them records the shortfall`() {
        val pen = createProduct(sku = "PEN-1", price = "2.50", stock = 10)
        val order = holdFourPens(pen)

        val report = importPens(onHand = 2)
        val warnings = report.issues.filter { it.kind == IssueKind.WARNING && it.sku == "PEN-1" }.flatMap { it.messages }
        assertTrue("The file has 2 on hand, but 4 are held by orders waiting for payment" in warnings, warnings.toString())
        assertEquals(-2, availableOf(pen))
        assertEquals(0, productService.get(pen).available)

        gateway.charge(ChargeRequest(order.publicId, order.total, order.currency, FakePaymentGateway.APPROVED_CARD))
        assertEquals(1, reconciler.reconcile(Instant.now().plus(Duration.ofHours(1))))

        assertEquals(0, stockOf(pen))
        assertEquals(0, availableOf(pen))
        val missing = jdbc.sql("SELECT details ->> 'missing' FROM audit_event WHERE action = 'STOCK_SHORTFALL'")
            .query(String::class.java)
            .single()
        assertEquals("""[{"sku": "PEN-1", "units": 2}]""", missing)
    }

    @Test
    fun `imports the example file and explains every row it did not take as is`() {
        val report = importer.import("products.csv", sampleCsv())

        assertEquals(97, report.rowsRead)
        assertEquals(2, report.blankRows)
        assertEquals(85, report.created)
        assertEquals(0, report.updated)
        assertEquals(7, report.rejected)
        assertEquals(3, report.conflicts)
        assertEquals(0, report.duplicates)
        assertEquals(1, report.warnings)
        assertEquals(85L, products.count())

        val rejected = report.issues.filter { it.kind == IssueKind.REJECTED }.map { it.line to it.sku }
        assertEquals(
            listOf(4L to "WM-042", 7L to "YM-015", 16L to "DL-007", 25L to "HD-099", 41L to "WS-001", 47L to "MB-001", 52L to "GC-025"),
            rejected,
        )
        assertEquals(
            listOf(36L to "RS-001", 56L to "BS-021", 89L to "BS-021"),
            report.issues.filter { it.kind == IssueKind.CONFLICT }.map { it.line to it.sku },
        )
        assertEquals(listOf("GK-088"), report.issues.filter { it.kind == IssueKind.WARNING }.map { it.sku })
    }

    @Test
    fun `the last row of a repeated SKU wins`() {
        importer.import("products.csv", sampleCsv())

        val shoes = products.findBySku("RS-001")!!
        assertEquals(BigDecimal("94.99"), shoes.price)
        assertEquals(120, shoes.stock)
        val speaker = products.findBySku("BS-021")!!
        assertEquals(BigDecimal("59.99"), speaker.price)
        assertEquals(110, speaker.stock)
    }

    @Test
    fun `stores hostile names as plain text and keeps the table`() {
        importer.import("products.csv", sampleCsv())

        assertEquals("<script>alert('xss')</script>", products.findBySku("XS-001")!!.name)
        assertEquals("Robert'); DROP TABLE products;--", products.findBySku("SQL-001")!!.name)
        assertNull(products.findBySku("GK-088")!!.weightKg)
    }

    @Test
    fun `importing the same file again changes nothing`() {
        importer.import("products.csv", sampleCsv())
        val again = importer.import("products.csv", sampleCsv())

        assertEquals(0, again.created)
        assertEquals(0, again.updated)
        assertEquals(85, again.unchanged)
    }

    @Test
    fun `a new file updates changed products and restores archived ones`() {
        importer.import("products.csv", sampleCsv())
        val ball = products.findBySku("SB-020")!!
        productService.archive(ball.id, ball.version)
        jdbc.sql("UPDATE product SET stock = 1 WHERE sku = 'RS-050'").update()

        val again = importer.import("products.csv", sampleCsv())

        assertEquals(2, again.updated)
        assertEquals(83, again.unchanged)
        assertEquals(false, products.findBySku("SB-020")!!.archived)
        assertEquals(200, products.findBySku("RS-050")!!.stock)
    }

    @Test
    fun `a file that fails to parse changes nothing`() {
        importer.import("products.csv", sampleCsv())
        val broken = "name,sku\nPen,PN-1\n".toByteArray()

        assertFailsWith<ImportFileException> { importer.import("broken.csv", broken) }

        assertEquals(85L, products.count())
    }

    @Test
    fun `a row with a zero byte is rejected and the other rows still go in`() {
        val csv = "name,sku,description,category,price,stock,weight_kg\n" +
            "Pen,PN-1,Blue,Office,1.00,3,0.01\nInk,IN-1,Black\u0000,Office,2.00,3,0.01\n"

        val report = importer.import("mixed.csv", csv.toByteArray())

        assertEquals(1, report.created)
        assertEquals(listOf("Description contains control characters"), report.issues.single().messages)
        assertEquals(1L, products.count())
    }

    @Test
    fun `a long file name with control characters is cleaned and shortened before it is stored`() {
        val csv = "name,sku,description,category,price,stock,weight_kg\nPen,PN-1,,Office,1.00,3,0.01\n"

        val report = importer.import("x".repeat(300) + "\u0000\r\n.csv", csv.toByteArray())

        assertEquals("x".repeat(255), report.fileName)
        val stored = jdbc.sql("SELECT entity_id FROM audit_event WHERE action = 'IMPORT_COMPLETED'").query(String::class.java).single()
        assertEquals(report.fileName, stored)
    }
}

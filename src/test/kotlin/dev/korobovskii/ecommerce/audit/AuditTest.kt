package dev.korobovskii.ecommerce.audit

import dev.korobovskii.ecommerce.IntegrationTest
import dev.korobovskii.ecommerce.catalog.ProductDraft
import dev.korobovskii.ecommerce.catalog.importing.ProductImporter
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditTest : IntegrationTest() {

    @Autowired
    lateinit var audit: AuditLog

    @Autowired
    lateinit var importer: ProductImporter

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `product changes and imports leave a trail with before and after values`() {
        val id = createProduct(sku = "PEN-1", price = "2.50", stock = 10, name = "Pen")
        val product = productService.get(id)
        productService.update(
            id, product.version,
            ProductDraft("PEN-1", "Pen", null, product.category, BigDecimal("3.00"), 10, null),
        )
        productService.archive(id, product.version + 1)
        importer.import("products.csv", sampleCsv())

        val actions = audit.recent(50).map { it.action }
        assertTrue(actions.containsAll(listOf("PRODUCT_CREATED", "PRODUCT_UPDATED", "PRODUCT_ARCHIVED", "IMPORT_COMPLETED")))
        val priceChange = jdbc.sql(
            """
            SELECT details -> 'changes' -> 'price' ->> 'from' || '>' || (details -> 'changes' -> 'price' ->> 'to')
            FROM audit_event WHERE action = 'PRODUCT_UPDATED'
            """.trimIndent(),
        ).query(String::class.java).single()
        assertEquals("2.50>3.00", priceChange)
        val created = jdbc.sql("SELECT details ->> 'created' FROM audit_event WHERE action = 'IMPORT_COMPLETED'")
            .query(String::class.java).single()
        assertEquals("85", created)
    }

    @Test
    fun `an order records who placed it and how it was settled`() {
        val id = createProduct(price = "2.50", stock = 10)
        mockMvc.post("/api/orders") {
            with(csrf())
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"items":[{"productId":$id,"quantity":1}],"expectedTotal":"2.50",
                 "customerName":"Ada Buyer","customerEmail":"ada@example.com","cardNumber":"4242424242424242"}
            """.trimIndent()
        }.andExpect { status { isCreated() } }

        val events = audit.recent(10, entityType = "order").map { it.actor to it.action }
        assertEquals(listOf("guest" to "ORDER_PAID", "guest" to "ORDER_PLACED"), events)
    }
}

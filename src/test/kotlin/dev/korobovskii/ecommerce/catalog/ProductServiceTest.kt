package dev.korobovskii.ecommerce.catalog

import dev.korobovskii.ecommerce.IntegrationTest
import dev.korobovskii.ecommerce.catalog.importing.ProductImporter
import dev.korobovskii.ecommerce.ordering.CheckoutItem
import dev.korobovskii.ecommerce.ordering.CheckoutRequest
import dev.korobovskii.ecommerce.ordering.CheckoutService
import dev.korobovskii.ecommerce.ordering.FakePaymentGateway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProductServiceTest : IntegrationTest() {

    @Autowired
    lateinit var checkout: CheckoutService

    @Autowired
    lateinit var importer: ProductImporter

    private fun draft(product: Product, stock: Int = product.stock, sku: String = product.sku) = ProductDraft(
        sku = sku,
        name = product.name,
        description = product.description,
        category = product.category,
        price = product.price,
        stock = stock,
        weightKg = product.weightKg,
    )

    @Test
    fun `an edit made from a stale form does not overwrite a sale`() {
        val id = createProduct(price = "2.50", stock = 10)
        val openedForm = productService.get(id)
        checkout.placeOrder(
            CheckoutRequest(
                UUID.randomUUID(),
                listOf(CheckoutItem(id, 3)),
                BigDecimal("7.50"),
                "Ada Buyer",
                "ada@example.com",
                FakePaymentGateway.APPROVED_CARD,
            ),
        )

        assertFailsWith<StaleProductException> {
            productService.update(id, openedForm.version, draft(openedForm, stock = 10))
        }
        assertEquals(7, productService.get(id).stock)
    }

    @Test
    fun `an edit from a fresh form is saved`() {
        val id = createProduct(stock = 10)
        val product = productService.get(id)

        productService.update(id, product.version, draft(product, stock = 25))

        val saved = productService.get(id)
        assertEquals(25, saved.stock)
        assertEquals(product.version + 1, saved.version)
    }

    @Test
    fun `a SKU can be used only once`() {
        createProduct(sku = "PEN-1")
        assertFailsWith<SkuTakenException> { createProduct(sku = "PEN-1") }
    }

    @Test
    fun `categories match regardless of case`() {
        createProduct(sku = "PEN-1", category = "Office")
        createProduct(sku = "PEN-2", category = "office")

        assertEquals(listOf("Office"), productService.categories().map { it.name })
    }

    @Test
    fun `archived products leave the shop and come back on restore`() {
        val id = createProduct(sku = "PEN-1", name = "Blue pen")
        productService.archive(id, 0)
        assertTrue(productService.search(ProductSearch(text = "pen")).items.isEmpty())
        assertEquals(1, productService.search(ProductSearch(text = "pen", includeArchived = true)).items.size)

        productService.restore(id, 1)
        assertEquals(1, productService.search(ProductSearch(text = "pen")).items.size)
    }

    @Test
    fun `search finds words, word parts and SKUs`() {
        importer.import("products.csv", sampleCsv())

        assertEquals(setOf("BS-021", "BS-099", "SS-022"), skus(ProductSearch(text = "speaker")))
        assertEquals(setOf("BS-021", "BS-099"), skus(ProductSearch(text = "blue")))
        assertEquals(setOf("RS-001"), skus(ProductSearch(text = "rs-001")))
        assertEquals(setOf("RS-001", "RS-050"), skus(ProductSearch(text = "running shoes")))
    }

    @Test
    fun `search treats wildcard characters as text`() {
        importer.import("products.csv", sampleCsv())

        assertTrue(skus(ProductSearch(text = "%")).isEmpty())
        assertTrue(skus(ProductSearch(text = "_")).isEmpty())
    }

    @Test
    fun `filters, sorting and paging`() {
        importer.import("products.csv", sampleCsv())
        val electronics = productService.categories().first { it.name == "Electronics" }

        assertEquals(15L, productService.search(ProductSearch(categoryId = electronics.id)).total)
        val cheap = productService.search(ProductSearch(minPrice = BigDecimal("10"), maxPrice = BigDecimal("20"), size = 100)).items
        assertTrue(cheap.isNotEmpty() && cheap.all { it.price >= BigDecimal("10") && it.price <= BigDecimal("20") })
        assertTrue("VC-001" !in skus(ProductSearch(inStockOnly = true, size = 100)))
        assertTrue("VC-001" in skus(ProductSearch(size = 100)))

        val byPrice = productService.search(ProductSearch(sort = ProductSort.PRICE_ASC, size = 100)).items.map { it.price }
        assertEquals(byPrice.sorted(), byPrice)

        val firstPage = productService.search(ProductSearch(sort = ProductSort.NAME, size = 10))
        assertEquals(85L, firstPage.total)
        assertEquals(9, firstPage.totalPages)
        assertEquals(10, firstPage.items.size)
        assertEquals(5, productService.search(ProductSearch(sort = ProductSort.NAME, size = 10, page = 8)).items.size)
    }

    private fun skus(search: ProductSearch): Set<String> = productService.search(search).items.map { it.sku }.toSet()
}

package dev.korobovskii.ecommerce

import dev.korobovskii.ecommerce.catalog.ProductDraft
import dev.korobovskii.ecommerce.catalog.ProductService
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal

@SpringBootTest(properties = ["app.admin.password=test-password", "app.rate-limit.enabled=false"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
abstract class IntegrationTest {

    @Autowired
    protected lateinit var jdbc: JdbcClient

    @Autowired
    protected lateinit var productService: ProductService

    @BeforeEach
    fun cleanDatabase() {
        jdbc.sql("TRUNCATE order_line, customer_order, product, category, audit_event RESTART IDENTITY CASCADE").update()
        jdbc.sql("DELETE FROM account WHERE role = 'CUSTOMER'").update()
        jdbc.sql("UPDATE account SET failed_logins = 0, locked_until = NULL").update()
        jdbc.sql("DELETE FROM spring_session").update()
    }

    protected fun sampleCsv(): ByteArray = ClassPathResource("sample/products.csv").inputStream.use { it.readBytes() }

    protected fun createProduct(
        sku: String = "TEST-001",
        price: String = "10.00",
        stock: Int = 10,
        name: String = "Test product",
        category: String = "Tests",
    ): Long = productService.create(
        ProductDraft(
            sku = sku,
            name = name,
            description = null,
            category = category,
            price = BigDecimal(price),
            stock = stock,
            weightKg = null,
        ),
    )

    protected fun stockOf(productId: Long): Int =
        jdbc.sql("SELECT stock FROM product WHERE id = :id").param("id", productId).query(Int::class.javaObjectType).single()

    protected fun availableOf(productId: Long): Int =
        jdbc.sql("SELECT stock - reserved FROM product WHERE id = :id").param("id", productId).query(Int::class.javaObjectType).single()
}

package dev.korobovskii.ecommerce.catalog.importing

import dev.korobovskii.ecommerce.IntegrationTest
import dev.korobovskii.ecommerce.ordering.OrderRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals

class ImportConcurrencyTest : IntegrationTest() {

    @Autowired
    lateinit var importer: ProductImporter

    @Autowired
    lateinit var orders: OrderRepository

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    lateinit var dataSource: DataSource

    private fun csv(skus: List<Int>, stock: Int): ByteArray {
        val header = "name,sku,description,category,price,stock,weight_kg"
        val rows = skus.map { "Item $it,IT-$it,,Category ${it % 7},9.99,$stock,0.1" }
        return (listOf(header) + rows).joinToString("\n").toByteArray()
    }

    @Test
    fun `imports that touch the same SKUs in opposite order do not deadlock`() {
        val skus = (1..300).toList()
        repeat(3) { round ->
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val forward = pool.submit(Callable { start.await(); importer.import("forward.csv", csv(skus, 10 + round)) })
                val backward = pool.submit(Callable { start.await(); importer.import("backward.csv", csv(skus.reversed(), 20 + round)) })
                start.countDown()
                assertEquals(0, forward.get().rejected)
                assertEquals(0, backward.get().rejected)
            } finally {
                pool.shutdownNow()
            }
        }
        assertEquals(300L, jdbc.sql("SELECT count(*) FROM product").query(Long::class.javaObjectType).single())
    }

    @Test
    fun `an import does not deadlock with a checkout that holds the same products`() {
        val first = createProduct(sku = "ZZ-1", stock = 10)
        val second = createProduct(sku = "AA-1", stock = 10)
        val file = "name,sku,description,category,price,stock,weight_kg\nZed,ZZ-1,,Tests,10.00,50,\nAce,AA-1,,Tests,10.00,50,\n"
        val pool = Executors.newSingleThreadExecutor()
        try {
            val import = TransactionTemplate(transactionManager).execute {
                orders.reserve(first, 1)
                val import = pool.submit(Callable { importer.import("stock.csv", file.toByteArray()) })
                awaitImportWaitingForLock()
                orders.reserve(second, 1)
                import
            }
            assertEquals(2, import.get(30, TimeUnit.SECONDS).updated)
        } finally {
            pool.shutdownNow()
        }
        assertEquals(50, stockOf(first))
        assertEquals(50, stockOf(second))
    }

    private fun awaitImportWaitingForLock() {
        val deadline = Instant.now().plusSeconds(10)
        while (!importWaitsForLock()) {
            check(Instant.now().isBefore(deadline)) { "The import never waited for a lock" }
            Thread.sleep(10)
        }
    }

    private fun importWaitsForLock(): Boolean =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%product%'",
                ).use { rows -> rows.next() && rows.getLong(1) > 0 }
            }
        }
}

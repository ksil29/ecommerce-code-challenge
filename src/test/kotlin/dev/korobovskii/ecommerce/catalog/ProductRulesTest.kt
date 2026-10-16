package dev.korobovskii.ecommerce.catalog

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductRulesTest {

    private fun input(
        sku: String = "AB-001",
        name: String = "Thing",
        description: String = "",
        category: String = "Misc",
        price: String = "10.00",
        stock: String = "5",
        weight: String = "0.5",
    ) = ProductInput(sku, name, description, category, price, stock, weight)

    private fun problemFields(input: ProductInput): List<String> =
        assertIs<ProductCheck.Invalid>(ProductRules.check(input)).problems.map { it.field }

    @Test
    fun `refuses control characters in the name, description and category`() {
        assertEquals(
            listOf("name", "description", "category"),
            problemFields(input(name = "Pen\u0000", description = "Blue\u0000", category = "Office\u0007")),
        )
    }

    @Test
    fun `accepts a clean row and normalizes it`() {
        val valid = assertIs<ProductCheck.Valid>(
            ProductRules.check(input(sku = " ab-001 ", name = "  Thing ", description = "  ", price = "10", weight = "0.5")),
        )
        assertEquals("AB-001", valid.draft.sku)
        assertEquals("Thing", valid.draft.name)
        assertNull(valid.draft.description)
        assertEquals(BigDecimal("10.00"), valid.draft.price)
        assertEquals(BigDecimal("0.500"), valid.draft.weightKg)
        assertTrue(valid.warnings.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["\$29.99", "free", "0.00", "0", "-1", "1,000.00", "12.345", "1e3", ""])
    fun `rejects prices that are not plain positive decimals`(price: String) {
        assertEquals(listOf("price"), problemFields(input(price = price)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["-5", "1.5", "ten", "", "1000001"])
    fun `rejects stock that is not a whole number in range`(stock: String) {
        assertEquals(listOf("stock"), problemFields(input(stock = stock)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "     ", "\t"])
    fun `rejects blank names`(name: String) {
        assertEquals(listOf("name"), problemFields(input(name = name)))
    }

    @Test
    fun `rejects a blank category`() {
        assertEquals(listOf("category"), problemFields(input(category = " ")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "AB 001", "AB--001", "-AB", "AB_001"])
    fun `rejects malformed SKUs`(sku: String) {
        assertEquals(listOf("sku"), problemFields(input(sku = sku)))
    }

    @Test
    fun `reports every problem of a row at once`() {
        assertEquals(listOf("name", "price", "stock"), problemFields(input(name = "", price = "free", stock = "-5")))
    }

    @Test
    fun `accepts zero stock and zero weight`() {
        val valid = assertIs<ProductCheck.Valid>(ProductRules.check(input(stock = "0", weight = "0")))
        assertEquals(0, valid.draft.stock)
        assertEquals(BigDecimal("0.000"), valid.draft.weightKg)
    }

    @Test
    fun `saves a missing weight as empty with a warning`() {
        val valid = assertIs<ProductCheck.Valid>(ProductRules.check(input(weight = "")))
        assertNull(valid.draft.weightKg)
        assertEquals(listOf("weight_kg"), valid.warnings.map { it.field })
    }

    @Test
    fun `keeps markup and SQL in names as plain text`() {
        val script = "<script>alert('xss')</script>"
        val sql = "Robert'); DROP TABLE products;--"
        assertEquals(script, assertIs<ProductCheck.Valid>(ProductRules.check(input(name = script))).draft.name)
        assertEquals(sql, assertIs<ProductCheck.Valid>(ProductRules.check(input(name = sql))).draft.name)
    }
}

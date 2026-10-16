package dev.korobovskii.ecommerce.catalog.importing

import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CsvProductReaderTest {

    private val reader = CsvProductReader(maxRows = 1000)
    private val header = "name,sku,description,category,price,stock,weight_kg"

    private fun read(text: String) = reader.read(text.toByteArray())

    @Test
    fun `reads the example file with its quoting and line numbers`() {
        val rows = reader.read(ClassPathResource("sample/products.csv").inputStream.use { it.readBytes() })
        assertEquals(97, rows.size)
        assertEquals(listOf(62L, 63L), rows.filterIsInstance<CsvRow.Blank>().map { it.line })
        val byLine = rows.filterIsInstance<CsvRow.Data>().associateBy { it.line }
        assertEquals("Comma, In Product Name", byLine.getValue(53).input.name)
        assertEquals("Quote \"Inside\" Name", byLine.getValue(59).input.name)
        assertEquals("<script>alert('xss')</script>", byLine.getValue(20).input.name)
        assertEquals("PRJ-001", byLine.getValue(98).input.sku)
    }

    @Test
    fun `strips a byte order mark and trims header names`() {
        val rows = read("﻿ Name , SKU ,description,category,price,stock,weight_kg\r\nPen,PN-1,,Office,1.00,3,0.01\r\n")
        val data = assertIs<CsvRow.Data>(rows.single())
        assertEquals("PN-1", data.input.sku)
        assertEquals(2, data.line)
    }

    @Test
    fun `accepts columns in any order`() {
        val rows = read("sku,weight_kg,stock,price,category,description,name\nPN-1,0.01,3,1.00,Office,,Pen\n")
        assertEquals("Pen", assertIs<CsvRow.Data>(rows.single()).input.name)
    }

    @Test
    fun `rejects a file with missing columns`() {
        val error = assertFailsWith<ImportFileException> { read("name,sku,price\nPen,PN-1,1.00\n") }
        assertTrue(error.message!!.contains("description"))
    }

    @Test
    fun `marks rows with the wrong number of fields`() {
        val rows = read("$header\nPen,PN-1,,Office,1.00,3\n")
        assertIs<CsvRow.Malformed>(rows.single())
    }

    @Test
    fun `rejects text that is not UTF-8`() {
        val bytes = "$header\nCaf".toByteArray() + byteArrayOf(0xE9.toByte()) + ",CF-1,,Food,1.00,1,0.1\n".toByteArray()
        assertFailsWith<ImportFileException> { reader.read(bytes) }
    }

    @Test
    fun `rejects an unterminated quote`() {
        assertFailsWith<ImportFileException> { read("$header\n\"Pen,PN-1,,Office,1.00,3,0.01\n") }
    }

    @Test
    fun `rejects a header line that is not valid CSV`() {
        val error = assertFailsWith<ImportFileException> { read("\"$header\nPen,PN-1,,Office,1.00,3,0.01\n") }
        assertTrue(error.message!!.startsWith("The file is not valid CSV"))
    }

    @Test
    fun `stops at the row limit`() {
        val small = CsvProductReader(maxRows = 2)
        val text = header + "\n" + (1..3).joinToString("\n") { "Pen,PN-$it,,Office,1.00,3,0.01" }
        assertFailsWith<ImportFileException> { small.read(text.toByteArray()) }
    }

    @Test
    fun `rejects an empty file`() {
        assertFailsWith<ImportFileException> { read("") }
    }
}

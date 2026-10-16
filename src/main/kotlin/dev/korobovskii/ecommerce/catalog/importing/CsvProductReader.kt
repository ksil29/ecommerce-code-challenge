package dev.korobovskii.ecommerce.catalog.importing

import dev.korobovskii.ecommerce.catalog.ProductInput
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

class ImportFileException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

sealed interface CsvRow {
    val line: Long

    data class Data(override val line: Long, val input: ProductInput) : CsvRow

    data class Blank(override val line: Long) : CsvRow

    data class Malformed(override val line: Long, val reason: String) : CsvRow
}

class CsvProductReader(private val maxRows: Int) {

    fun read(bytes: ByteArray): List<CsvRow> {
        val text = decode(bytes).removePrefix("\uFEFF")
        if (text.isBlank()) throw ImportFileException("The file is empty")
        return try {
            parse(text)
        } catch (e: UncheckedIOException) {
            throw ImportFileException("The file is not valid CSV: ${e.cause?.message ?: e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw ImportFileException("The file is not valid CSV: ${e.message}", e)
        } catch (e: IllegalStateException) {
            throw ImportFileException("The file is not valid CSV: ${e.message}", e)
        } catch (e: IOException) {
            throw ImportFileException("The file is not valid CSV: ${e.message}", e)
        }
    }

    private fun parse(text: String): List<CsvRow> =
        CSVParser.parse(text, FORMAT).use { parser ->
            val header = parser.headerNames.map { it.trim().lowercase() }
            val missing = COLUMNS.filterNot { it in header }
            if (missing.isNotEmpty()) {
                throw ImportFileException("Missing columns: ${missing.joinToString()}. Expected: ${COLUMNS.joinToString()}")
            }
            val index = COLUMNS.associateWith { header.indexOf(it) }
            val rows = mutableListOf<CsvRow>()
            val records = parser.iterator()
            while (true) {
                val line = parser.currentLineNumber + 1
                if (!records.hasNext()) break
                val record = records.next()
                if (rows.size >= maxRows) throw ImportFileException("The file has more than $maxRows rows")
                rows += toRow(line, record, header.size, index)
            }
            rows
        }

    private fun toRow(line: Long, record: CSVRecord, width: Int, index: Map<String, Int>): CsvRow {
        val values = record.toList()
        if (values.all { it.isBlank() }) return CsvRow.Blank(line)
        if (values.size != width) return CsvRow.Malformed(line, "Expected $width fields, found ${values.size}")
        fun value(column: String) = values[index.getValue(column)]
        return CsvRow.Data(
            line,
            ProductInput(
                sku = value("sku"),
                name = value("name"),
                description = value("description"),
                category = value("category"),
                price = value("price"),
                stock = value("stock"),
                weightKg = value("weight_kg"),
            ),
        )
    }

    private fun decode(bytes: ByteArray): String =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw ImportFileException("The file is not valid UTF-8 text", e)
        }

    companion object {
        val COLUMNS = listOf("name", "sku", "description", "category", "price", "stock", "weight_kg")

        private val FORMAT: CSVFormat = CSVFormat.RFC4180.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(false)
            .setTrim(false)
            .get()
    }
}

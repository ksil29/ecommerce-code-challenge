package dev.korobovskii.ecommerce.catalog.importing

import dev.korobovskii.ecommerce.audit.AuditLog
import dev.korobovskii.ecommerce.audit.ShopMetrics
import dev.korobovskii.ecommerce.catalog.CategoryRepository
import dev.korobovskii.ecommerce.catalog.ProductCheck
import dev.korobovskii.ecommerce.catalog.ProductDraft
import dev.korobovskii.ecommerce.catalog.ProductRepository
import dev.korobovskii.ecommerce.catalog.ProductRules
import dev.korobovskii.ecommerce.catalog.UpsertResult
import dev.korobovskii.ecommerce.config.AppProperties
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class ProductImporter(
    private val products: ProductRepository,
    private val categories: CategoryRepository,
    private val audit: AuditLog,
    private val metrics: ShopMetrics,
    private val observations: ObservationRegistry,
    properties: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val reader = CsvProductReader(properties.csvImport.maxRows)

    @Transactional
    fun import(fileName: String, bytes: ByteArray): ImportReport {
        val name = fileName.replace(CONTROL_CHARACTERS, "").trim().take(FILE_NAME_MAX).ifEmpty { "upload.csv" }
        return Observation.createNotStarted("shop.import", observations)
            .highCardinalityKeyValue("file", name)
            .observe<ImportReport> { importRows(name, bytes) }
    }

    private fun importRows(fileName: String, bytes: ByteArray): ImportReport {
        val rows = reader.read(bytes)
        val issues = mutableListOf<ImportIssue>()
        val accepted = LinkedHashMap<String, AcceptedRow>()
        var blankRows = 0
        for (row in rows) {
            when (row) {
                is CsvRow.Blank -> blankRows++
                is CsvRow.Malformed -> issues += ImportIssue(row.line, null, IssueKind.REJECTED, listOf(row.reason))
                is CsvRow.Data -> accept(row, accepted, issues)
            }
        }

        val toWrite = accepted.values.sortedBy { it.draft.sku }
        val categoryIds = toWrite.map { it.draft.category }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
            .associate { it.lowercase() to categories.idFor(it) }
        val held = products.lockForImport(toWrite.map { it.draft.sku })
        var created = 0
        var updated = 0
        var unchanged = 0
        for (row in toWrite) {
            val reserved = held[row.draft.sku] ?: 0
            if (row.draft.stock < reserved) {
                issues += ImportIssue(
                    row.line, row.draft.sku, IssueKind.WARNING,
                    listOf("The file has ${row.draft.stock} on hand, but $reserved are held by orders waiting for payment"),
                )
            }
            when (products.upsert(row.draft, categoryIds.getValue(row.draft.category.lowercase()))) {
                UpsertResult.CREATED -> created++
                UpsertResult.UPDATED -> updated++
                UpsertResult.UNCHANGED -> unchanged++
            }
        }

        val report = ImportReport(
            fileName = fileName,
            rowsRead = rows.size,
            blankRows = blankRows,
            created = created,
            updated = updated,
            unchanged = unchanged,
            issues = issues.sortedWith(compareBy<ImportIssue> { it.line }.thenBy { it.kind }),
        )
        record(report)
        return report
    }

    private fun record(report: ImportReport) {
        log.info(
            "Imported {}: {} rows, {} created, {} updated, {} unchanged, {} rejected, {} conflicts, {} duplicates, {} blank",
            report.fileName, report.rowsRead, report.created, report.updated, report.unchanged,
            report.rejected, report.conflicts, report.duplicates, report.blankRows,
        )
        metrics.importRows("created", report.created)
        metrics.importRows("updated", report.updated)
        metrics.importRows("unchanged", report.unchanged)
        metrics.importRows("rejected", report.rejected)
        metrics.importRows("conflict", report.conflicts)
        audit.record(
            "IMPORT_COMPLETED", "import", report.fileName,
            mapOf(
                "rows" to report.rowsRead,
                "created" to report.created,
                "updated" to report.updated,
                "unchanged" to report.unchanged,
                "rejected" to report.rejected,
                "conflicts" to report.conflicts,
            ),
        )
    }

    private fun accept(row: CsvRow.Data, accepted: MutableMap<String, AcceptedRow>, issues: MutableList<ImportIssue>) {
        when (val check = ProductRules.check(row.input)) {
            is ProductCheck.Invalid -> {
                val sku = row.input.sku?.trim()?.uppercase()?.ifEmpty { null }
                issues += ImportIssue(row.line, sku, IssueKind.REJECTED, check.problems.map { it.message })
            }
            is ProductCheck.Valid -> {
                val draft = check.draft
                if (check.warnings.isNotEmpty()) {
                    issues += ImportIssue(row.line, draft.sku, IssueKind.WARNING, check.warnings.map { it.message })
                }
                val previous = accepted[draft.sku]
                when {
                    previous == null -> accepted[draft.sku] = AcceptedRow(row.line, draft)
                    previous.draft == draft -> issues += ImportIssue(
                        row.line, draft.sku, IssueKind.DUPLICATE,
                        listOf("Same as line ${previous.line}; skipped"),
                    )
                    else -> {
                        issues += ImportIssue(
                            row.line, draft.sku, IssueKind.CONFLICT,
                            listOf("Replaces line ${previous.line}: ${changes(previous.draft, draft)}"),
                        )
                        accepted[draft.sku] = AcceptedRow(row.line, draft)
                    }
                }
            }
        }
    }

    private fun changes(old: ProductDraft, new: ProductDraft): String {
        val parts = mutableListOf<String>()
        if (old.name != new.name) parts += "name"
        if (old.description != new.description) parts += "description"
        if (old.category != new.category) parts += "category ${old.category} → ${new.category}"
        if (old.price != new.price) parts += "price ${old.price.plain()} → ${new.price.plain()}"
        if (old.stock != new.stock) parts += "stock ${old.stock} → ${new.stock}"
        if (old.weightKg != new.weightKg) parts += "weight ${old.weightKg.plain()} → ${new.weightKg.plain()}"
        return parts.joinToString(", ")
    }

    private fun BigDecimal?.plain(): String = this?.stripTrailingZeros()?.toPlainString() ?: "empty"

    private data class AcceptedRow(val line: Long, val draft: ProductDraft)

    private companion object {
        const val FILE_NAME_MAX = 255
        val CONTROL_CHARACTERS = Regex("\\p{Cntrl}")
    }
}

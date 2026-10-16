package dev.korobovskii.ecommerce.catalog.importing

enum class IssueKind(val label: String) {
    REJECTED("Rejected"),
    CONFLICT("Conflict"),
    DUPLICATE("Duplicate"),
    WARNING("Warning"),
}

data class ImportIssue(val line: Long, val sku: String?, val kind: IssueKind, val messages: List<String>)

data class ImportReport(
    val fileName: String,
    val rowsRead: Int,
    val blankRows: Int,
    val created: Int,
    val updated: Int,
    val unchanged: Int,
    val issues: List<ImportIssue>,
) {
    val rejected: Int
        get() = issues.count { it.kind == IssueKind.REJECTED }
    val conflicts: Int
        get() = issues.count { it.kind == IssueKind.CONFLICT }
    val duplicates: Int
        get() = issues.count { it.kind == IssueKind.DUPLICATE }
    val warnings: Int
        get() = issues.count { it.kind == IssueKind.WARNING }
}

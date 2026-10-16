package dev.korobovskii.ecommerce.catalog

import java.math.BigDecimal

enum class ProductSort(val label: String) {
    RELEVANCE("Relevance"),
    NAME("Name"),
    PRICE_ASC("Price: low to high"),
    PRICE_DESC("Price: high to low"),
    NEWEST("Newest"),
}

data class ProductSearch(
    val text: String? = null,
    val categoryId: Long? = null,
    val minPrice: BigDecimal? = null,
    val maxPrice: BigDecimal? = null,
    val inStockOnly: Boolean = false,
    val includeArchived: Boolean = false,
    val sort: ProductSort = ProductSort.RELEVANCE,
    val page: Int = 0,
    val size: Int = 20,
) {
    companion object {
        const val MAX_PAGE_SIZE = 100
    }

    init {
        require(page >= 0) { "page must not be negative" }
        require(size in 1..MAX_PAGE_SIZE) { "size must be between 1 and $MAX_PAGE_SIZE" }
    }
}

data class PageResult<T>(val items: List<T>, val page: Int, val size: Int, val total: Long) {
    val totalPages: Int
        get() = if (total == 0L) 0 else ((total + size - 1) / size).toInt()
    val hasPrevious: Boolean
        get() = page > 0
    val hasNext: Boolean
        get() = page + 1 < totalPages
}

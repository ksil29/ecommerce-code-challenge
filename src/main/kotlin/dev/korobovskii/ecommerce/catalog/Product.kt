package dev.korobovskii.ecommerce.catalog

import java.math.BigDecimal

data class Product(
    val id: Long,
    val sku: String,
    val name: String,
    val description: String?,
    val categoryId: Long,
    val category: String,
    val price: BigDecimal,
    val stock: Int,
    val weightKg: BigDecimal?,
    val archived: Boolean,
    val version: Long,
    val reserved: Int = 0,
) {
    val available: Int
        get() = (stock - reserved).coerceAtLeast(0)
}

data class ProductDraft(
    val sku: String,
    val name: String,
    val description: String?,
    val category: String,
    val price: BigDecimal,
    val stock: Int,
    val weightKg: BigDecimal?,
)

data class ProductInput(
    val sku: String? = null,
    val name: String? = null,
    val description: String? = null,
    val category: String? = null,
    val price: String? = null,
    val stock: String? = null,
    val weightKg: String? = null,
)

data class Category(val id: Long, val name: String)

class ProductNotFoundException(key: String) : RuntimeException("Product $key was not found")

class SkuTakenException(val sku: String, cause: Throwable? = null) : RuntimeException("SKU $sku already exists", cause)

class StaleProductException(val id: Long) : RuntimeException("Product $id was changed by someone else")

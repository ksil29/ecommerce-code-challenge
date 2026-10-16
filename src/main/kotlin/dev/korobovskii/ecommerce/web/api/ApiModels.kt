package dev.korobovskii.ecommerce.web.api

import dev.korobovskii.ecommerce.catalog.PageResult
import dev.korobovskii.ecommerce.catalog.Product
import dev.korobovskii.ecommerce.catalog.ProductInput
import dev.korobovskii.ecommerce.catalog.ProductSort
import dev.korobovskii.ecommerce.ordering.CartQuote
import dev.korobovskii.ecommerce.ordering.CheckoutItem
import dev.korobovskii.ecommerce.ordering.Order
import dev.korobovskii.ecommerce.ordering.OrderStatus
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class ProductDto(
    val id: Long,
    val sku: String,
    val name: String,
    val description: String?,
    val categoryId: Long,
    val category: String,
    val price: String,
    val stock: Int,
    val reserved: Int,
    val available: Int,
    val weightKg: String?,
    val archived: Boolean,
    val version: Long,
) {
    companion object {
        fun of(product: Product) = ProductDto(
            id = product.id,
            sku = product.sku,
            name = product.name,
            description = product.description,
            categoryId = product.categoryId,
            category = product.category,
            price = product.price.toPlainString(),
            stock = product.stock,
            reserved = product.reserved,
            available = product.available,
            weightKg = product.weightKg?.stripTrailingZeros()?.toPlainString(),
            archived = product.archived,
            version = product.version,
        )
    }
}

data class PageDto<T>(val items: List<T>, val page: Int, val size: Int, val total: Long, val totalPages: Int) {
    companion object {
        fun <S, T> of(page: PageResult<S>, map: (S) -> T) =
            PageDto(page.items.map(map), page.page, page.size, page.total, page.totalPages)
    }
}

data class CategoryDto(val id: Long, val name: String)

data class ProductQuery(
    @field:Size(max = 200)
    @field:Pattern(regexp = "[^\\p{Cntrl}]*", message = "Remove control characters from the search")
    val q: String? = null,
    val categoryId: Long? = null,
    @field:PositiveOrZero
    val minPrice: BigDecimal? = null,
    @field:PositiveOrZero
    val maxPrice: BigDecimal? = null,
    val inStock: Boolean = false,
    val includeArchived: Boolean = false,
    val sort: ProductSort = ProductSort.RELEVANCE,
    @field:Min(0)
    @field:Max(10_000)
    val page: Int = 0,
    @field:Min(1)
    @field:Max(100)
    val size: Int = 20,
)

data class ProductRequest(
    val sku: String? = null,
    val name: String? = null,
    val description: String? = null,
    val category: String? = null,
    val price: String? = null,
    val stock: String? = null,
    val weightKg: String? = null,
    val version: Long? = null,
) {
    fun toInput() = ProductInput(sku, name, description, category, price, stock, weightKg)
}

data class VersionRequest(val version: Long)

data class ItemRequest(
    val productId: Long,
    val quantity: Int,
) {
    fun toItem() = CheckoutItem(productId, quantity)
}

data class QuoteRequest(
    @field:Size(max = 100)
    val items: List<ItemRequest> = emptyList(),
)

data class SettingsDto(
    val maxQuantity: Int,
    val paymentTimeoutSeconds: Long,
    val reconcileIntervalSeconds: Long,
    val passwordMinLength: Int,
    val passwordMaxLength: Int,
    val apiDocs: Boolean,
)

data class QuoteLineDto(val product: ProductDto, val quantity: Int, val lineTotal: String, val available: Boolean)

data class QuoteDto(val lines: List<QuoteLineDto>, val missingProductIds: List<Long>, val total: String, val canCheckout: Boolean) {
    companion object {
        fun of(quote: CartQuote) = QuoteDto(
            lines = quote.lines.map { QuoteLineDto(ProductDto.of(it.product), it.quantity, it.lineTotal.toPlainString(), it.available) },
            missingProductIds = quote.missingProductIds,
            total = quote.total.toPlainString(),
            canCheckout = quote.canCheckout,
        )
    }
}

data class OrderRequest(
    @field:NotEmpty(message = "The cart is empty")
    @field:Size(max = 100)
    val items: List<ItemRequest> = emptyList(),
    @field:NotBlank
    val expectedTotal: String = "",
    @field:NotBlank(message = "Enter your name")
    @field:Size(max = 200)
    @field:Pattern(regexp = "[^\\p{Cntrl}]*", message = "Enter your name without control characters")
    val customerName: String = "",
    @field:NotBlank(message = "Enter your email")
    @field:Email(message = "Enter a valid email address")
    @field:Size(max = 320)
    val customerEmail: String = "",
    @field:NotBlank(message = "Enter a card number")
    val cardNumber: String = "",
)

data class OrderLineDto(val sku: String, val productName: String, val unitPrice: String, val quantity: Int, val lineTotal: String)

data class OrderDto(
    val id: UUID,
    val status: OrderStatus,
    val statusLabel: String,
    val customerName: String,
    val customerEmail: String,
    val total: String,
    val currency: String,
    val cardLast4: String?,
    val paymentReference: String?,
    val failureReason: String?,
    val createdAt: Instant,
    val lines: List<OrderLineDto>,
) {
    companion object {
        fun of(order: Order) = OrderDto(
            id = order.publicId,
            status = order.status,
            statusLabel = order.status.label,
            customerName = order.customerName,
            customerEmail = order.customerEmail,
            total = order.total.toPlainString(),
            currency = order.currency,
            cardLast4 = order.cardLast4,
            paymentReference = order.paymentReference,
            failureReason = order.failureReason,
            createdAt = order.createdAt,
            lines = order.lines.map {
                OrderLineDto(it.sku, it.productName, it.unitPrice.toPlainString(), it.quantity, it.lineTotal.toPlainString())
            },
        )
    }
}

data class OrderSummaryDto(
    val id: UUID,
    val status: OrderStatus,
    val statusLabel: String,
    val customerName: String,
    val customerEmail: String,
    val total: String,
    val currency: String,
    val cardLast4: String?,
    val paymentReference: String?,
    val failureReason: String?,
    val createdAt: Instant,
) {
    companion object {
        fun of(order: Order) = OrderSummaryDto(
            id = order.publicId,
            status = order.status,
            statusLabel = order.status.label,
            customerName = order.customerName,
            customerEmail = order.customerEmail,
            total = order.total.toPlainString(),
            currency = order.currency,
            cardLast4 = order.cardLast4,
            paymentReference = order.paymentReference,
            failureReason = order.failureReason,
            createdAt = order.createdAt,
        )
    }
}

data class MeDto(
    val authenticated: Boolean,
    val username: String?,
    val admin: Boolean,
    val fullName: String? = null,
    val email: String? = null,
)

data class RegisterRequest(val email: String = "", val fullName: String = "", val password: String = "")

data class AuditEventDto(
    val id: Long,
    val occurredAt: Instant,
    val actor: String,
    val action: String,
    val entityType: String,
    val entityId: String?,
    val details: String,
)

package dev.korobovskii.ecommerce.ordering

import dev.korobovskii.ecommerce.catalog.Product
import dev.korobovskii.ecommerce.catalog.ProductRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal

data class QuoteLine(val product: Product, val quantity: Int) {
    val lineTotal: BigDecimal
        get() = product.price.multiply(BigDecimal(quantity))
    val available: Boolean
        get() = !product.archived && product.available >= quantity
}

data class CartQuote(val lines: List<QuoteLine>, val missingProductIds: List<Long>) {
    val total: BigDecimal
        get() = lines.fold(BigDecimal.ZERO) { sum, line -> sum + line.lineTotal }
    val canCheckout: Boolean
        get() = lines.isNotEmpty() && missingProductIds.isEmpty() && lines.all { it.available }
}

@Service
class CartQuoteService(private val products: ProductRepository) {

    fun quote(items: List<CheckoutItem>): CartQuote {
        val quantities = items.groupBy { it.productId }.mapValues { (_, same) -> same.sumOf { it.quantity } }
        val found = products.findAllById(quantities.keys).associateBy { it.id }
        val lines = quantities.mapNotNull { (id, quantity) -> found[id]?.let { QuoteLine(it, quantity) } }
        return CartQuote(lines, quantities.keys.filterNot { it in found })
    }
}

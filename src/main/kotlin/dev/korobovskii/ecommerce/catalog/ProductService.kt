package dev.korobovskii.ecommerce.catalog

import dev.korobovskii.ecommerce.audit.AuditLog
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ProductService(
    private val products: ProductRepository,
    private val categories: CategoryRepository,
    private val audit: AuditLog,
) {

    fun get(id: Long): Product = products.findById(id) ?: throw ProductNotFoundException(id.toString())

    fun findActiveBySku(sku: String): Product? = products.findBySku(sku)?.takeUnless { it.archived }

    fun search(search: ProductSearch): PageResult<Product> = products.search(search)

    fun categories(): List<Category> = categories.all()

    @Transactional
    fun create(draft: ProductDraft): Long {
        val categoryId = categories.idFor(draft.category)
        val id = try {
            products.insert(draft, categoryId)
        } catch (e: DuplicateKeyException) {
            throw SkuTakenException(draft.sku, e)
        }
        audit.record(
            "PRODUCT_CREATED", "product", id.toString(),
            mapOf("sku" to draft.sku, "name" to draft.name, "price" to draft.price.toPlainString(), "stock" to draft.stock),
        )
        return id
    }

    @Transactional
    fun update(id: Long, version: Long, draft: ProductDraft) {
        val before = get(id)
        val categoryId = categories.idFor(draft.category)
        val updated = try {
            products.update(id, version, draft, categoryId)
        } catch (e: DuplicateKeyException) {
            throw SkuTakenException(draft.sku, e)
        }
        if (!updated) failUpdate(id)
        audit.record("PRODUCT_UPDATED", "product", id.toString(), mapOf("sku" to draft.sku, "changes" to changes(before, draft)))
    }

    @Transactional
    fun archive(id: Long, version: Long) {
        val product = get(id)
        if (!products.setArchived(id, version, true)) failUpdate(id)
        audit.record("PRODUCT_ARCHIVED", "product", id.toString(), mapOf("sku" to product.sku))
    }

    @Transactional
    fun restore(id: Long, version: Long) {
        val product = get(id)
        if (!products.setArchived(id, version, false)) failUpdate(id)
        audit.record("PRODUCT_RESTORED", "product", id.toString(), mapOf("sku" to product.sku))
    }

    private fun changes(before: Product, after: ProductDraft): Map<String, Map<String, Any?>> {
        val changes = linkedMapOf<String, Map<String, Any?>>()
        fun compare(field: String, old: Any?, new: Any?) {
            if (old != new) changes[field] = mapOf("from" to old, "to" to new)
        }
        compare("sku", before.sku, after.sku)
        compare("name", before.name, after.name)
        compare("description", before.description, after.description)
        compare("category", before.category, after.category)
        compare("price", before.price.toPlainString(), after.price.toPlainString())
        compare("stock", before.stock, after.stock)
        compare("weightKg", before.weightKg?.stripTrailingZeros()?.toPlainString(), after.weightKg?.stripTrailingZeros()?.toPlainString())
        return changes
    }

    private fun failUpdate(id: Long): Nothing {
        if (products.findById(id) == null) throw ProductNotFoundException(id.toString())
        throw StaleProductException(id)
    }
}

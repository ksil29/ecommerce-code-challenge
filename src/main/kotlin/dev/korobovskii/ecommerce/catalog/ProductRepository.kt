package dev.korobovskii.ecommerce.catalog

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Types

enum class UpsertResult { CREATED, UPDATED, UNCHANGED }

@Repository
class ProductRepository(private val jdbc: JdbcClient) {

    private val mapper = RowMapper { rs, _ ->
        Product(
            id = rs.getLong("id"),
            sku = rs.getString("sku"),
            name = rs.getString("name"),
            description = rs.getString("description"),
            categoryId = rs.getLong("category_id"),
            category = rs.getString("category_name"),
            price = rs.getBigDecimal("price"),
            stock = rs.getInt("stock"),
            weightKg = rs.getBigDecimal("weight_kg"),
            archived = rs.getBoolean("archived"),
            version = rs.getLong("version"),
            reserved = rs.getInt("reserved"),
        )
    }

    fun findById(id: Long): Product? =
        jdbc.sql("$SELECT WHERE p.id = :id").param("id", id).query(mapper).optional().orElse(null)

    fun findBySku(sku: String): Product? =
        jdbc.sql("$SELECT WHERE p.sku = :sku").param("sku", sku.trim().uppercase()).query(mapper).optional().orElse(null)

    fun findAllById(ids: Collection<Long>): List<Product> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            jdbc.sql("$SELECT WHERE p.id IN (:ids)").param("ids", ids.toList()).query(mapper).list()
        }

    fun count(): Long = jdbc.sql("SELECT count(*) FROM product").query(Long::class.javaObjectType).single()

    fun insert(draft: ProductDraft, categoryId: Long): Long =
        jdbc.sql(
            """
            INSERT INTO product (sku, name, description, category_id, price, stock, weight_kg)
            VALUES (:sku, :name, :description, :categoryId, :price, :stock, :weightKg)
            RETURNING id
            """.trimIndent(),
        ).paramSource(params(draft, categoryId)).query(Long::class.javaObjectType).single()

    fun update(id: Long, version: Long, draft: ProductDraft, categoryId: Long): Boolean =
        jdbc.sql(
            """
            UPDATE product
            SET sku = :sku, name = :name, description = :description, category_id = :categoryId,
                price = :price, stock = :stock, weight_kg = :weightKg,
                version = version + 1, updated_at = now()
            WHERE id = :id AND version = :version
            """.trimIndent(),
        ).paramSource(params(draft, categoryId).addValue("id", id).addValue("version", version)).update() == 1

    fun setArchived(id: Long, version: Long, archived: Boolean): Boolean =
        jdbc.sql(
            """
            UPDATE product SET archived = :archived, version = version + 1, updated_at = now()
            WHERE id = :id AND version = :version
            """.trimIndent(),
        ).param("archived", archived).param("id", id).param("version", version).update() == 1

    fun lockForImport(skus: Collection<String>): Map<String, Int> {
        if (skus.isEmpty()) return emptyMap()
        return jdbc.sql("SELECT sku, reserved FROM product WHERE sku IN (:skus) ORDER BY id FOR UPDATE")
            .param("skus", skus)
            .query { rs, _ -> rs.getString("sku") to rs.getInt("reserved") }
            .list()
            .toMap()
    }

    fun upsert(draft: ProductDraft, categoryId: Long): UpsertResult {
        val inserted = jdbc.sql(
            """
            INSERT INTO product (sku, name, description, category_id, price, stock, weight_kg)
            VALUES (:sku, :name, :description, :categoryId, :price, :stock, :weightKg)
            ON CONFLICT (sku) DO UPDATE
            SET name = EXCLUDED.name, description = EXCLUDED.description, category_id = EXCLUDED.category_id,
                price = EXCLUDED.price, stock = EXCLUDED.stock, weight_kg = EXCLUDED.weight_kg,
                archived = FALSE, version = product.version + 1, updated_at = now()
            WHERE (product.name, product.description, product.category_id, product.price,
                   product.stock, product.weight_kg, product.archived)
                IS DISTINCT FROM
                  (EXCLUDED.name, EXCLUDED.description, EXCLUDED.category_id, EXCLUDED.price,
                   EXCLUDED.stock, EXCLUDED.weight_kg, FALSE)
            RETURNING (xmax = 0) AS inserted
            """.trimIndent(),
        ).paramSource(params(draft, categoryId)).query(Boolean::class.javaObjectType).optional()
        return when {
            inserted.isEmpty -> UpsertResult.UNCHANGED
            inserted.get() -> UpsertResult.CREATED
            else -> UpsertResult.UPDATED
        }
    }

    fun search(search: ProductSearch): PageResult<Product> {
        val conditions = mutableListOf<String>()
        val params = MapSqlParameterSource()
        if (!search.includeArchived) conditions += "p.archived = FALSE"
        val text = search.text?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null) {
            conditions += "(p.search_vector @@ websearch_to_tsquery('english', :text) " +
                "OR p.name ILIKE :pattern ESCAPE '\\' OR p.sku ILIKE :pattern ESCAPE '\\')"
            params.addValue("text", text).addValue("pattern", "%" + escapeLike(text) + "%")
        }
        search.categoryId?.let {
            conditions += "p.category_id = :categoryId"
            params.addValue("categoryId", it)
        }
        search.minPrice?.let {
            conditions += "p.price >= :minPrice"
            params.addValue("minPrice", it)
        }
        search.maxPrice?.let {
            conditions += "p.price <= :maxPrice"
            params.addValue("maxPrice", it)
        }
        if (search.inStockOnly) conditions += "p.stock > p.reserved"

        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        val order = when (search.sort) {
            ProductSort.RELEVANCE ->
                if (text != null) {
                    "ts_rank(p.search_vector, websearch_to_tsquery('english', :text)) DESC, p.name, p.id"
                } else {
                    "p.name, p.id"
                }
            ProductSort.NAME -> "p.name, p.id"
            ProductSort.PRICE_ASC -> "p.price, p.id"
            ProductSort.PRICE_DESC -> "p.price DESC, p.id"
            ProductSort.NEWEST -> "p.created_at DESC, p.id DESC"
        }
        val total = jdbc.sql("SELECT count(*) FROM product p $where")
            .paramSource(params)
            .query(Long::class.javaObjectType)
            .single()
        params.addValue("limit", search.size).addValue("offset", search.page.toLong() * search.size)
        val items = jdbc.sql("$SELECT $where ORDER BY $order LIMIT :limit OFFSET :offset")
            .paramSource(params)
            .query(mapper)
            .list()
        return PageResult(items, search.page, search.size, total)
    }

    private fun params(draft: ProductDraft, categoryId: Long): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("sku", draft.sku)
            .addValue("name", draft.name)
            .addValue("description", draft.description, Types.VARCHAR)
            .addValue("categoryId", categoryId)
            .addValue("price", draft.price)
            .addValue("stock", draft.stock)
            .addValue("weightKg", draft.weightKg, Types.NUMERIC)

    private fun escapeLike(text: String): String =
        text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private companion object {
        const val SELECT = """
            SELECT p.id, p.sku, p.name, p.description, p.category_id, c.name AS category_name,
                   p.price, p.stock, p.reserved, p.weight_kg, p.archived, p.version
            FROM product p
            JOIN category c ON c.id = p.category_id
        """
    }
}

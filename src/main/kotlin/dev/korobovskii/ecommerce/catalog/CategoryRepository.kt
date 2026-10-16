package dev.korobovskii.ecommerce.catalog

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class CategoryRepository(private val jdbc: JdbcClient) {

    private val mapper = RowMapper { rs, _ -> Category(rs.getLong("id"), rs.getString("name")) }

    fun all(): List<Category> =
        jdbc.sql("SELECT id, name FROM category ORDER BY lower(name)").query(mapper).list()

    fun idFor(name: String): Long {
        jdbc.sql("INSERT INTO category (name) VALUES (:name) ON CONFLICT ((lower(name))) DO NOTHING")
            .param("name", name)
            .update()
        return jdbc.sql("SELECT id FROM category WHERE lower(name) = lower(:name)")
            .param("name", name)
            .query(Long::class.javaObjectType)
            .single()
    }
}

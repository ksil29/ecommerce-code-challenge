package dev.korobovskii.ecommerce.audit

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime

data class AuditEvent(
    val id: Long,
    val occurredAt: Instant,
    val actor: String,
    val action: String,
    val entityType: String,
    val entityId: String?,
    val details: String,
)

@Component
class AuditLog(private val jdbc: JdbcClient, private val json: JsonMapper) {

    private val mapper = RowMapper { rs, _ ->
        AuditEvent(
            id = rs.getLong("id"),
            occurredAt = rs.getObject("occurred_at", OffsetDateTime::class.java).toInstant(),
            actor = rs.getString("actor"),
            action = rs.getString("action"),
            entityType = rs.getString("entity_type"),
            entityId = rs.getString("entity_id"),
            details = rs.getString("details"),
        )
    }

    fun record(
        action: String,
        entityType: String,
        entityId: String?,
        details: Map<String, Any?> = emptyMap(),
        actor: String = currentActor(),
    ) {
        jdbc.sql(
            """
            INSERT INTO audit_event (actor, action, entity_type, entity_id, details)
            VALUES (:actor, :action, :entityType, :entityId, CAST(:details AS jsonb))
            """.trimIndent(),
        ).param("actor", actor)
            .param("action", action)
            .param("entityType", entityType)
            .param("entityId", entityId, Types.VARCHAR)
            .param("details", json.writeValueAsString(details))
            .update()
    }

    fun recent(limit: Int, entityType: String? = null, entityId: String? = null): List<AuditEvent> {
        val conditions = mutableListOf<String>()
        if (entityType != null) conditions += "entity_type = :entityType"
        if (entityId != null) conditions += "entity_id = :entityId"
        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        var statement = jdbc.sql("SELECT * FROM audit_event $where ORDER BY occurred_at DESC, id DESC LIMIT :limit")
            .param("limit", limit)
        if (entityType != null) statement = statement.param("entityType", entityType)
        if (entityId != null) statement = statement.param("entityId", entityId)
        return statement.query(mapper).list()
    }

    private fun currentActor(): String {
        val authentication = SecurityContextHolder.getContext().authentication ?: return "system"
        return if (authentication is AnonymousAuthenticationToken) "guest" else authentication.name
    }
}

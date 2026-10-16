package dev.korobovskii.ecommerce.account

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Types
import java.time.Duration
import java.time.OffsetDateTime

@Repository
class AccountRepository(private val jdbc: JdbcClient) {

    private val mapper = RowMapper { rs, _ ->
        Account(
            id = rs.getLong("id"),
            username = rs.getString("username"),
            email = rs.getString("email"),
            fullName = rs.getString("full_name"),
            role = Role.valueOf(rs.getString("role")),
            passwordHash = rs.getString("password_hash"),
            lockedUntil = rs.getObject("locked_until", OffsetDateTime::class.java)?.toInstant(),
        )
    }

    fun findByUsername(username: String): Account? =
        jdbc.sql("SELECT * FROM account WHERE lower(username) = lower(:username)")
            .param("username", username.trim())
            .query(mapper)
            .optional()
            .orElse(null)

    fun insert(username: String, email: String?, fullName: String, role: Role, passwordHash: String): Long =
        jdbc.sql(
            """
            INSERT INTO account (username, email, full_name, role, password_hash)
            VALUES (:username, :email, :fullName, :role, :passwordHash)
            RETURNING id
            """.trimIndent(),
        ).param("username", username)
            .param("email", email, Types.VARCHAR)
            .param("fullName", fullName)
            .param("role", role.name)
            .param("passwordHash", passwordHash)
            .query(Long::class.javaObjectType)
            .single()

    fun updatePassword(id: Long, passwordHash: String) {
        jdbc.sql("UPDATE account SET password_hash = :hash, updated_at = now() WHERE id = :id")
            .param("hash", passwordHash)
            .param("id", id)
            .update()
    }

    fun recordFailedLogin(username: String, maxAttempts: Int, lockFor: Duration): Boolean =
        jdbc.sql(
            """
            UPDATE account
            SET failed_logins = CASE WHEN failed_logins + 1 >= :max THEN 0 ELSE failed_logins + 1 END,
                locked_until = CASE WHEN failed_logins + 1 >= :max
                                    THEN now() + make_interval(secs => :lockSeconds)
                                    ELSE locked_until END,
                updated_at = now()
            WHERE lower(username) = lower(:username)
            RETURNING coalesce(locked_until > now(), FALSE)
            """.trimIndent(),
        ).param("username", username.trim())
            .param("max", maxAttempts)
            .param("lockSeconds", lockFor.toSeconds().toDouble())
            .query(Boolean::class.javaObjectType)
            .optional()
            .orElse(false)

    fun clearFailedLogins(username: String) {
        jdbc.sql(
            """
            UPDATE account SET failed_logins = 0, locked_until = NULL, updated_at = now()
            WHERE lower(username) = lower(:username) AND (failed_logins > 0 OR locked_until IS NOT NULL)
            """.trimIndent(),
        ).param("username", username.trim()).update()
    }
}

package dev.korobovskii.ecommerce.account

import dev.korobovskii.ecommerce.IntegrationTest
import dev.korobovskii.ecommerce.audit.AuditLog
import dev.korobovskii.ecommerce.config.AppProperties
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExtendWith(OutputCaptureExtension::class)
class AdminBootstrapTest : IntegrationTest() {

    @Autowired
    lateinit var accounts: AccountRepository

    @Autowired
    lateinit var encoder: PasswordEncoder

    @Autowired
    lateinit var policy: PasswordPolicy

    @Autowired
    lateinit var audit: AuditLog

    @Autowired
    lateinit var sessions: FindByIndexNameSessionRepository<out Session>

    private val username = "operator"

    @AfterEach
    fun removeAdmin() {
        jdbc.sql("DELETE FROM account WHERE username = :username").param("username", username).update()
    }

    private fun bootstrap(password: String, allowWeakPassword: Boolean = false) {
        val properties = AppProperties(admin = AppProperties.Admin(username, password, allowWeakPassword))
        AdminBootstrap(accounts, encoder, policy, sessions, properties, audit).run(DefaultApplicationArguments())
    }

    private fun adminSessions() =
        jdbc.sql("SELECT count(*) FROM spring_session WHERE principal_name = :username").param("username", username)
            .query(Int::class.javaObjectType).single()

    @Test
    fun `a new password ends the sessions the admin had`() {
        bootstrap("first long passphrase")
        jdbc.sql(
            """
            INSERT INTO spring_session (primary_id, session_id, creation_time, last_access_time,
                                        max_inactive_interval, expiry_time, principal_name)
            VALUES (:id, :id, :now, :now, 1800, :now + 1800000, :username)
            """.trimIndent(),
        ).param("id", UUID.randomUUID().toString()).param("now", System.currentTimeMillis()).param("username", username).update()

        bootstrap("first long passphrase")
        assertEquals(1, adminSessions())

        bootstrap("second long passphrase")
        assertEquals(0, adminSessions())
    }

    private fun passwordHash() = accounts.findByUsername(username)?.passwordHash

    @Test
    fun `the first start needs ADMIN_PASSWORD`() {
        val error = assertFailsWith<IllegalStateException> { bootstrap("") }
        assertEquals("ADMIN_PASSWORD is not set, and the admin account 'operator' does not exist yet", error.message)
        assertNull(passwordHash())
    }

    @Test
    fun `a password that breaks the policy stops the start, and the error does not repeat it`() {
        val common = assertFailsWith<IllegalStateException> { bootstrap("Qwertyuiop12") }
        assertEquals("ADMIN_PASSWORD does not meet the password policy: This password is too common", common.message)

        val personal = assertFailsWith<IllegalStateException> { bootstrap("operator of the night shift") }
        assertEquals("ADMIN_PASSWORD does not meet the password policy: The password must not contain your email or name", personal.message)
        assertNull(passwordHash())
    }

    @Test
    fun `creates the admin, keeps the password when the setting is empty, and changes it when the setting changes`() {
        bootstrap("first long passphrase")
        val created = accounts.findByUsername(username)!!
        assertEquals(Role.ADMIN, created.role)
        assertTrue(encoder.matches("first long passphrase", created.passwordHash))

        bootstrap("")
        bootstrap("first long passphrase")
        assertEquals(created.passwordHash, passwordHash())

        bootstrap("second long passphrase")
        assertTrue(encoder.matches("second long passphrase", passwordHash()))

        val events = jdbc.sql("SELECT actor, action FROM audit_event WHERE entity_type = 'account' ORDER BY id")
            .query { rs, _ -> rs.getString("actor") to rs.getString("action") }
            .list()
        assertEquals(listOf("system" to "ADMIN_CREATED", "system" to "ADMIN_PASSWORD_CHANGED"), events)
    }

    @Test
    fun `the local profile may keep a weak demo password, with a warning that leaves the password out`(output: CapturedOutput) {
        bootstrap("swordfish", allowWeakPassword = true)

        assertTrue(encoder.matches("swordfish", passwordHash()))
        assertTrue("does not meet the password policy (Use at least 12 characters)" in output.out, output.out)
        assertFalse("swordfish" in output.out)
    }
}

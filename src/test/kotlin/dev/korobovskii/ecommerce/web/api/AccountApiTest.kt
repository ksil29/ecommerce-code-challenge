package dev.korobovskii.ecommerce.web.api

import dev.korobovskii.ecommerce.IntegrationTest
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(OutputCaptureExtension::class)
class AccountApiTest : IntegrationTest() {

    @Autowired
    lateinit var mockMvc: MockMvc

    private val passphrase = "a long and quiet passphrase"

    private fun register(email: String, password: String, fullName: String = "Grace Hopper") =
        mockMvc.post("/api/auth/register") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","fullName":"$fullName","password":"$password"}"""
        }

    private fun login(username: String, password: String) =
        mockMvc.post("/api/auth/login") {
            with(csrf())
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            param("username", username)
            param("password", password)
        }

    private fun placeOrder(productId: Long, session: Cookie?) =
        mockMvc.post("/api/orders") {
            with(csrf())
            session?.let { cookie(it) }
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"items":[{"productId":$productId,"quantity":1}],"expectedTotal":"2.50",
                 "customerName":"Grace Hopper","customerEmail":"grace@example.com","cardNumber":"4242424242424242"}
            """.trimIndent()
        }

    @Test
    fun `a customer's order is shown only to that customer and to admins, a guest order to whoever has its link`() {
        register("grace@example.com", passphrase).andExpect { status { isCreated() } }
        val grace = login("grace@example.com", passphrase).andReturn().response.getCookie("SESSION")!!
        register("alan@example.com", passphrase, fullName = "Alan Turing").andExpect { status { isCreated() } }
        val alan = login("alan@example.com", passphrase).andReturn().response.getCookie("SESSION")!!
        val productId = createProduct(price = "2.50", stock = 10)

        val customerOrder = placeOrder(productId, grace).andReturn().response.getHeader("Location")!!
        val guestOrder = placeOrder(productId, null).andReturn().response.getHeader("Location")!!

        mockMvc.get(customerOrder) { cookie(grace) }.andExpect { status { isOk() } }
        mockMvc.get(customerOrder) { cookie(alan) }.andExpect { status { isNotFound() } }
        mockMvc.get(customerOrder).andExpect { status { isNotFound() } }
        mockMvc.get(customerOrder) { with(user("admin").roles("ADMIN")) }.andExpect { status { isOk() } }
        mockMvc.get(guestOrder).andExpect { status { isOk() } }
    }

    @Test
    fun `a customer registers, signs in and sees only their own orders`() {
        register("grace@example.com", passphrase).andExpect {
            status { isCreated() }
            jsonPath("$.username") { value("grace@example.com") }
        }
        val session = login("Grace@Example.com", passphrase)
            .andExpect { status { isNoContent() } }
            .andReturn().response.getCookie("SESSION")!!

        mockMvc.get("/api/auth/me") { cookie(session) }.andExpect {
            jsonPath("$.authenticated") { value(true) }
            jsonPath("$.admin") { value(false) }
            jsonPath("$.fullName") { value("Grace Hopper") }
            jsonPath("$.email") { value("grace@example.com") }
        }
        val productId = createProduct(price = "2.50", stock = 10)
        placeOrder(productId, session).andExpect { status { isCreated() } }
        placeOrder(productId, null).andExpect { status { isCreated() } }

        mockMvc.get("/api/me/orders") { cookie(session) }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].total") { value("2.50") }
            jsonPath("$[0].lines") { doesNotExist() }
        }
        mockMvc.get("/api/admin/orders") { cookie(session) }.andExpect { status { isForbidden() } }
        mockMvc.get("/api/me/orders").andExpect { status { isUnauthorized() } }

        val hash = jdbc.sql("SELECT password_hash FROM account WHERE username = 'grace@example.com'").query(String::class.java).single()
        assertTrue(hash.startsWith("{argon2}"))
    }

    @Test
    fun `registration enforces the password policy and unique emails`() {
        register("not-an-email", passphrase).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("email") }
        }
        register("grace@example.com", "short").andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].message") { value("Use at least 12 characters") }
        }
        register("grace@example.com", "password1234").andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].message") { value("This password is too common") }
        }
        register("grace@example.com", passphrase, fullName = "Grace\\u0000Hopper").andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("fullName") }
        }
        register("grace@example.com", passphrase).andExpect { status { isCreated() } }
        register("GRACE@example.com", "another long passphrase").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("EMAIL_TAKEN") }
        }
    }

    @Test
    fun `five wrong passwords lock the account for a while, logged by account id rather than email`(output: CapturedOutput) {
        register("grace@example.com", passphrase).andExpect { status { isCreated() } }
        repeat(5) { login("grace@example.com", "a wrong passphrase here").andExpect { status { isUnauthorized() } } }

        login("grace@example.com", passphrase).andExpect { status { isUnauthorized() } }
        val lockedSeconds = jdbc.sql("SELECT extract(epoch FROM locked_until - now()) FROM account WHERE username = 'grace@example.com'")
            .query(Double::class.javaObjectType).single()
        assertTrue(lockedSeconds in 14.0 * 60..15.0 * 60, lockedSeconds.toString())
        assertEquals(
            listOf("guest"),
            jdbc.sql("SELECT actor FROM audit_event WHERE action = 'ACCOUNT_LOCKED'").query(String::class.java).list(),
        )
        val id = jdbc.sql("SELECT id FROM account WHERE username = 'grace@example.com'").query(Long::class.javaObjectType).single()
        assertTrue("Account $id is locked for" in output.all)
        assertFalse("grace@example.com" in output.all)

        jdbc.sql("UPDATE account SET locked_until = now() - interval '1 second' WHERE username = 'grace@example.com'").update()
        login("grace@example.com", passphrase).andExpect { status { isNoContent() } }
    }

    @Test
    fun `a successful sign-in clears earlier failures`() {
        register("grace@example.com", passphrase).andExpect { status { isCreated() } }
        repeat(2) { login("grace@example.com", "a wrong passphrase here").andExpect { status { isUnauthorized() } } }
        assertEquals(2, failedLogins("grace@example.com"))

        login("grace@example.com", passphrase).andExpect { status { isNoContent() } }

        assertEquals(0, failedLogins("grace@example.com"))
    }

    private fun failedLogins(username: String) =
        jdbc.sql("SELECT failed_logins FROM account WHERE username = :username").param("username", username)
            .query(Int::class.javaObjectType).single()

    @Test
    fun `an unknown user and a wrong password get the same answer`() {
        register("grace@example.com", passphrase).andExpect { status { isCreated() } }
        val unknown = login("nobody@example.com", passphrase).andReturn().response
        val wrong = login("grace@example.com", "a wrong passphrase here").andReturn().response
        assertEquals(unknown.status, wrong.status)
        assertEquals(unknown.contentAsString, wrong.contentAsString)
    }
}

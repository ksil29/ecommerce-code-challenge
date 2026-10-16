package dev.korobovskii.ecommerce.account

import dev.korobovskii.ecommerce.config.AppProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PasswordPolicyTest {

    private val policy = PasswordPolicy(AppProperties())

    private fun messages(password: String) = policy.check(password, "ada.lovelace@example.com", "Ada Lovelace").map { it.message }

    @Test
    fun `the lengths come from the settings, which refuse values below NIST's and OWASP's floors`() {
        val strict = PasswordPolicy(AppProperties(security = AppProperties.Security(password = AppProperties.Password(minLength = 16))))
        assertEquals(listOf("Use at least 16 characters"), strict.check("fourteen chars", "ada@example.com", "Ada").map { it.message })

        assertFailsWith<IllegalArgumentException> { AppProperties.Password(minLength = 6) }
        assertFailsWith<IllegalArgumentException> { AppProperties.Password(minLength = 12, maxLength = 10) }
        assertFailsWith<IllegalArgumentException> { AppProperties.Argon2(memoryKib = 4_096) }
        assertFailsWith<IllegalArgumentException> { AppProperties.Argon2(memoryKib = 7_168, iterations = 2) }
        AppProperties.Argon2(memoryKib = 7_168, iterations = 5)
    }

    @ParameterizedTest
    @ValueSource(strings = ["correct horse battery staple", "Тихо шуршит листва 2026", "v9#Lq!t2Rw8&zP"])
    fun `accepts long passphrases without composition rules`(password: String) {
        assertTrue(messages(password).isEmpty())
    }

    @Test
    fun `rejects short and very long passwords`() {
        assertEquals(listOf("Use at least 12 characters"), messages("Short1!pass"))
        assertEquals(listOf("Use at most 128 characters"), messages("x".repeat(64) + "y".repeat(65)))
    }

    @Test
    fun `counts characters, not bytes`() {
        assertTrue(messages("ñ".repeat(6) + "ü".repeat(6)).none { it.startsWith("Use at least") })
    }

    @ParameterizedTest
    @ValueSource(strings = ["password1234", "Qwertyuiop12", "123456789012"])
    fun `rejects common passwords regardless of case`(password: String) {
        assertEquals(listOf("This password is too common"), messages(password))
    }

    @Test
    fun `rejects repeated characters and personal data`() {
        assertEquals(listOf("This password is too easy to guess"), messages("aaaaaaaaaaaaaa"))
        assertEquals(listOf("The password must not contain your email or name"), messages("my ada.lovelace secret"))
        assertEquals(listOf("The password must not contain your email or name"), messages("LOVELACE rules forever"))
    }
}

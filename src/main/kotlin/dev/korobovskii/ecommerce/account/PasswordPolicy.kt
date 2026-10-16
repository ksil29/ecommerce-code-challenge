package dev.korobovskii.ecommerce.account

import dev.korobovskii.ecommerce.catalog.FieldProblem
import dev.korobovskii.ecommerce.config.AppProperties
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.text.Normalizer

@Component
class PasswordPolicy(properties: AppProperties) {

    private val minLength = properties.security.password.minLength
    private val maxLength = properties.security.password.maxLength

    private val commonPasswords: Set<String> =
        ClassPathResource("security/common-passwords.txt").inputStream.bufferedReader().useLines { lines ->
            lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        }

    fun check(password: String, username: String, fullName: String): List<FieldProblem> {
        val normalized = Normalizer.normalize(password, Normalizer.Form.NFKC)
        val length = normalized.codePointCount(0, normalized.length)
        val lowered = normalized.lowercase()
        val problems = mutableListOf<FieldProblem>()
        when {
            length < minLength -> problems += FieldProblem("password", "Use at least $minLength characters")
            length > maxLength -> problems += FieldProblem("password", "Use at most $maxLength characters")
            lowered in commonPasswords -> problems += FieldProblem("password", "This password is too common")
            normalized.toSet().size == 1 -> problems += FieldProblem("password", "This password is too easy to guess")
            containsPersonalData(lowered, username, fullName) ->
                problems += FieldProblem("password", "The password must not contain your email or name")
        }
        return problems
    }

    private fun containsPersonalData(password: String, username: String, fullName: String): Boolean {
        val parts = listOf(username.substringBefore('@')) + fullName.split(Regex("\\s+"))
        return parts.map { it.trim().lowercase() }.any { it.length >= MIN_PERSONAL_PART && password.contains(it) }
    }

    private companion object {
        const val MIN_PERSONAL_PART = 4
    }
}

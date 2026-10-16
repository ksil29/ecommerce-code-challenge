package dev.korobovskii.ecommerce.account

import java.time.Instant

enum class Role { CUSTOMER, ADMIN }

data class Account(
    val id: Long,
    val username: String,
    val email: String?,
    val fullName: String,
    val role: Role,
    val passwordHash: String,
    val lockedUntil: Instant?,
) {
    fun lockedAt(now: Instant): Boolean = lockedUntil?.isAfter(now) == true
}

class UsernameTakenException(cause: Throwable? = null) : RuntimeException("An account with this email already exists", cause)

package dev.korobovskii.ecommerce.account

import dev.korobovskii.ecommerce.audit.AuditLog
import dev.korobovskii.ecommerce.catalog.FieldProblem
import dev.korobovskii.ecommerce.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

class InvalidAccountException(val problems: List<FieldProblem>) : RuntimeException("The account is not valid")

data class Registration(val email: String, val fullName: String, val password: String)

@Service
class AccountService(
    private val accounts: AccountRepository,
    private val policy: PasswordPolicy,
    private val encoder: PasswordEncoder,
    private val audit: AuditLog,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    fun register(registration: Registration): Account {
        val email = registration.email.trim().lowercase()
        val fullName = registration.fullName.trim()
        val problems = mutableListOf<FieldProblem>()
        if (!EMAIL.matches(email) || email.length > EMAIL_MAX) problems += FieldProblem("email", "Enter a valid email address")
        if (fullName.isEmpty() || fullName.length > NAME_MAX || CONTROL.containsMatchIn(fullName)) {
            problems += FieldProblem("fullName", "Enter your name (up to $NAME_MAX characters)")
        }
        problems += policy.check(registration.password, email, fullName)
        if (problems.isNotEmpty()) throw InvalidAccountException(problems)

        val hash = encoder.encode(registration.password)!!
        return transaction.execute {
            val id = try {
                accounts.insert(email, email, fullName, Role.CUSTOMER, hash)
            } catch (e: DuplicateKeyException) {
                throw UsernameTakenException(e)
            }
            audit.record("ACCOUNT_REGISTERED", "account", id.toString(), mapOf("username" to email))
            accounts.findByUsername(email)!!
        }
    }

    fun find(username: String): Account? = accounts.findByUsername(username)

    private companion object {
        const val EMAIL_MAX = 320
        const val NAME_MAX = 200
        val EMAIL = Regex("[^@\\s\\p{Cntrl}]+@[^@\\s\\p{Cntrl}]+\\.[^@\\s\\p{Cntrl}]+")
        val CONTROL = Regex("\\p{Cntrl}")
    }
}

@Component
class AccountUserDetailsService(private val accounts: AccountRepository, private val clock: Clock) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails {
        val account = accounts.findByUsername(username) ?: throw UsernameNotFoundException("Unknown user")
        return User.withUsername(account.username)
            .password(account.passwordHash)
            .roles(account.role.name)
            .accountLocked(account.lockedAt(clock.instant()))
            .build()
    }
}

@Component
class LoginAttempts(
    private val accounts: AccountRepository,
    private val properties: AppProperties,
    private val audit: AuditLog,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    @Transactional
    fun onFailure(event: AuthenticationFailureBadCredentialsEvent) {
        val username = event.authentication.name ?: return
        val locked = accounts.recordFailedLogin(username, properties.security.maxFailedLogins, properties.security.lockDuration)
        if (locked) {
            val accountId = accounts.findByUsername(username)?.id
            log.warn("Account {} is locked for {} after repeated failed logins", accountId, properties.security.lockDuration)
            audit.record("ACCOUNT_LOCKED", "account", username, mapOf("lockedFor" to properties.security.lockDuration.toString()), "guest")
        }
    }

    @EventListener
    fun onSuccess(event: AuthenticationSuccessEvent) {
        accounts.clearFailedLogins(event.authentication.name)
    }
}

@Component
@Order(0)
class AdminBootstrap(
    private val accounts: AccountRepository,
    private val encoder: PasswordEncoder,
    private val policy: PasswordPolicy,
    private val sessions: FindByIndexNameSessionRepository<out Session>,
    private val properties: AppProperties,
    private val audit: AuditLog,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun run(args: ApplicationArguments) {
        val admin = properties.admin
        val existing = accounts.findByUsername(admin.username)
        if (admin.password.isEmpty()) {
            check(existing != null) { "ADMIN_PASSWORD is not set, and the admin account '${admin.username}' does not exist yet" }
            return
        }
        checkPolicy(admin)
        when {
            existing == null -> {
                val id = accounts.insert(admin.username, null, ADMIN_NAME, Role.ADMIN, encoder.encode(admin.password)!!)
                audit.record("ADMIN_CREATED", "account", id.toString(), mapOf("username" to admin.username))
                log.info("Created the admin account {}", admin.username)
            }
            !encoder.matches(admin.password, existing.passwordHash) -> {
                accounts.updatePassword(existing.id, encoder.encode(admin.password)!!)
                sessions.findByPrincipalName(existing.username).keys.forEach(sessions::deleteById)
                audit.record("ADMIN_PASSWORD_CHANGED", "account", existing.id.toString(), mapOf("username" to admin.username))
                log.info("Set the password of the admin account {} from ADMIN_PASSWORD", admin.username)
            }
        }
    }

    private fun checkPolicy(admin: AppProperties.Admin) {
        val problems = policy.check(admin.password, admin.username, ADMIN_NAME).joinToString("; ") { it.message }
        when {
            problems.isEmpty() -> Unit
            admin.allowWeakPassword ->
                log.warn("The admin password does not meet the password policy ({}); allowed by app.admin.allow-weak-password", problems)
            else -> error("ADMIN_PASSWORD does not meet the password policy: $problems")
        }
    }

    private companion object {
        const val ADMIN_NAME = "Administrator"
    }
}

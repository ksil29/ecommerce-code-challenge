package dev.korobovskii.ecommerce.web.api

import dev.korobovskii.ecommerce.account.AccountService
import dev.korobovskii.ecommerce.account.Registration
import dev.korobovskii.ecommerce.catalog.FieldProblem
import dev.korobovskii.ecommerce.config.AppProperties
import dev.korobovskii.ecommerce.ordering.CardNumbers
import dev.korobovskii.ecommerce.ordering.CartQuoteService
import dev.korobovskii.ecommerce.ordering.CheckoutRequest
import dev.korobovskii.ecommerce.ordering.CheckoutService
import dev.korobovskii.ecommerce.ordering.Order
import dev.korobovskii.ecommerce.ordering.OrderRepository
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api")
class OrderApi(
    private val checkout: CheckoutService,
    private val quotes: CartQuoteService,
    private val orders: OrderRepository,
    private val accounts: AccountService,
    private val properties: AppProperties,
) {

    @PostMapping("/cart/quote")
    fun quote(@Valid @RequestBody request: QuoteRequest): QuoteDto {
        val problems = quantityProblems(request.items)
        if (problems.isNotEmpty()) throw InvalidRequestException(problems)
        return QuoteDto.of(quotes.quote(request.items.map { it.toItem() }))
    }

    @PostMapping("/orders")
    fun place(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: OrderRequest,
        authentication: Authentication?,
    ): ResponseEntity<OrderDto> {
        val problems = quantityProblems(request.items).toMutableList()
        val key = runCatching { UUID.fromString(idempotencyKey) }.getOrNull()
        if (key == null) problems += FieldProblem("idempotencyKey", "Idempotency-Key must be a UUID")
        val expectedTotal = request.expectedTotal.toBigDecimalOrNull()
        if (expectedTotal == null) problems += FieldProblem("expectedTotal", "Expected total must be a decimal number")
        if (!CardNumbers.isValid(request.cardNumber)) problems += FieldProblem("cardNumber", "Card number is not valid")
        if (problems.isNotEmpty() || key == null || expectedTotal == null) throw InvalidRequestException(problems)

        val order = checkout.placeOrder(
            CheckoutRequest(
                idempotencyKey = key,
                items = request.items.map { it.toItem() },
                expectedTotal = expectedTotal,
                customerName = request.customerName.trim(),
                customerEmail = request.customerEmail.trim(),
                cardNumber = request.cardNumber,
                accountId = authentication.signedInName()?.let { accounts.find(it)?.id },
            ),
        )
        return ResponseEntity.created(URI.create("/api/orders/${order.publicId}")).body(OrderDto.of(order))
    }

    @GetMapping("/orders/{id}")
    fun order(@PathVariable id: UUID, authentication: Authentication?): OrderDto {
        val order = orders.findByPublicId(id)?.takeIf { it.visibleTo(authentication) }
            ?: throw NotFoundException("Order $id was not found")
        return OrderDto.of(order)
    }

    private fun quantityProblems(items: List<ItemRequest>): List<FieldProblem> {
        val max = properties.orders.maxQuantity
        val perProduct = items.groupBy { it.productId }.mapValues { (_, same) -> same.sumOf { it.quantity.toLong() } }
        return items.mapIndexedNotNull { index, item ->
            when {
                item.quantity !in 1..max -> "Choose a quantity from 1 to $max"
                perProduct.getValue(item.productId) > max -> "Choose up to $max of one product in total"
                else -> null
            }?.let { FieldProblem("items[$index].quantity", it) }
        }
    }

    private fun Order.visibleTo(authentication: Authentication?): Boolean =
        accountId == null || authentication.isAdmin() || accountId == authentication.signedInName()?.let { accounts.find(it)?.id }
}

@RestController
@RequestMapping("/api")
class SettingsApi(
    private val properties: AppProperties,
    @param:Value("\${springdoc.swagger-ui.enabled:false}") private val apiDocs: Boolean,
) {

    @GetMapping("/settings")
    fun settings() = SettingsDto(
        maxQuantity = properties.orders.maxQuantity,
        paymentTimeoutSeconds = properties.orders.paymentTimeout.toSeconds(),
        reconcileIntervalSeconds = properties.orders.reconcileInterval.toSeconds(),
        passwordMinLength = properties.security.password.minLength,
        passwordMaxLength = properties.security.password.maxLength,
        apiDocs = apiDocs,
    )
}

@RestController
@RequestMapping("/api")
class AccountApi(private val accounts: AccountService, private val orders: OrderRepository, private val properties: AppProperties) {

    @GetMapping("/auth/me")
    fun me(authentication: Authentication?): MeDto {
        val name = authentication.signedInName() ?: return MeDto(authenticated = false, username = null, admin = false)
        val account = accounts.find(name)
        return MeDto(
            authenticated = true,
            username = name,
            admin = authentication.isAdmin(),
            fullName = account?.fullName,
            email = account?.email,
        )
    }

    @PostMapping("/auth/register")
    fun register(@RequestBody request: RegisterRequest): ResponseEntity<MeDto> {
        val account = accounts.register(Registration(request.email, request.fullName, request.password))
        val body = MeDto(
            authenticated = false,
            username = account.username,
            admin = false,
            fullName = account.fullName,
            email = account.email,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(body)
    }

    @GetMapping("/me/orders")
    fun myOrders(authentication: Authentication): List<OrderSummaryDto> {
        val account = accounts.find(authentication.name) ?: return emptyList()
        return orders.forAccount(account.id, properties.orders.historySize).map(OrderSummaryDto::of)
    }
}

fun Authentication?.signedInName(): String? =
    if (this == null || this is AnonymousAuthenticationToken || !isAuthenticated) null else name

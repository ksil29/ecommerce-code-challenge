package dev.korobovskii.ecommerce.ordering

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ChargeRequest(val orderId: UUID, val amount: BigDecimal, val currency: String, val cardNumber: String)

sealed interface ChargeResult {
    data class Approved(val reference: String) : ChargeResult

    data class Declined(val reason: String) : ChargeResult
}

class PaymentUnavailableException(message: String) : RuntimeException(message)

interface PaymentGateway {
    fun charge(request: ChargeRequest): ChargeResult

    fun find(orderId: UUID): ChargeResult?
}

@Component
class FakePaymentGateway : PaymentGateway {

    private val charges = ConcurrentHashMap<UUID, ChargeResult>()

    override fun charge(request: ChargeRequest): ChargeResult {
        charges[request.orderId]?.let { return it }
        val digits = CardNumbers.digits(request.cardNumber)
        if (digits == TIMEOUT_CARD) throw PaymentUnavailableException("The payment processor did not answer in time")
        val result = when {
            !CardNumbers.isValid(digits) -> ChargeResult.Declined("Card number is not valid")
            digits == DECLINED_CARD -> ChargeResult.Declined("Card declined")
            digits == NO_FUNDS_CARD -> ChargeResult.Declined("Insufficient funds")
            else -> ChargeResult.Approved("pay_" + UUID.randomUUID().toString().replace("-", "").take(20))
        }
        return charges.putIfAbsent(request.orderId, result) ?: result
    }

    override fun find(orderId: UUID): ChargeResult? = charges[orderId]

    companion object {
        const val APPROVED_CARD = "4242424242424242"
        const val DECLINED_CARD = "4000000000000002"
        const val NO_FUNDS_CARD = "4000000000009995"
        const val TIMEOUT_CARD = "4000000000000119"
    }
}

object CardNumbers {
    private const val MIN_DIGITS = 12
    private const val MAX_DIGITS = 19
    private const val MAX_DIGIT = 9
    private const val LUHN_MODULUS = 10
    private const val SHOWN_DIGITS = 4
    private val allowed = Regex("[0-9 -]{12,23}")

    fun digits(number: String): String = number.filter(Char::isDigit)

    fun lastFour(number: String): String = digits(number).takeLast(SHOWN_DIGITS)

    fun isValid(number: String): Boolean {
        if (!allowed.matches(number)) return false
        val digits = digits(number)
        if (digits.length !in MIN_DIGITS..MAX_DIGITS) return false
        val sum = digits.reversed().mapIndexed { index, char ->
            val digit = char - '0'
            if (index % 2 == 1) (digit * 2).let { if (it > MAX_DIGIT) it - MAX_DIGIT else it } else digit
        }.sum()
        return sum % LUHN_MODULUS == 0
    }
}

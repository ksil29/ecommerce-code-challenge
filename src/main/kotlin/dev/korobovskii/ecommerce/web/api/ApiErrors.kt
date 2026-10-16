package dev.korobovskii.ecommerce.web.api

import dev.korobovskii.ecommerce.account.InvalidAccountException
import dev.korobovskii.ecommerce.account.UsernameTakenException
import dev.korobovskii.ecommerce.catalog.FieldProblem
import dev.korobovskii.ecommerce.catalog.ProductNotFoundException
import dev.korobovskii.ecommerce.catalog.SkuTakenException
import dev.korobovskii.ecommerce.catalog.StaleProductException
import dev.korobovskii.ecommerce.catalog.importing.ImportFileException
import dev.korobovskii.ecommerce.ordering.IdempotencyKeyReusedException
import dev.korobovskii.ecommerce.ordering.OrderRefusedException
import dev.korobovskii.ecommerce.ordering.OutOfStockException
import dev.korobovskii.ecommerce.ordering.PriceChangedException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

class InvalidRequestException(val problems: List<FieldProblem>, message: String = "The request is not valid") :
    RuntimeException(message)

class NotFoundException(message: String) : RuntimeException(message)

@RestControllerAdvice
class ApiErrors : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val problems = ex.bindingResult.fieldErrors.map {
            FieldProblem(it.field, if (it.isBindingFailure) "This value is not valid" else it.defaultMessage ?: "This value is not valid")
        }
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "The request is not valid", errors = problems))
    }

    @ExceptionHandler(InvalidRequestException::class)
    fun invalid(e: InvalidRequestException) = respond(HttpStatus.BAD_REQUEST, e.message!!, errors = e.problems)

    @ExceptionHandler(InvalidAccountException::class)
    fun invalidAccount(e: InvalidAccountException) = respond(HttpStatus.BAD_REQUEST, e.message!!, errors = e.problems)

    @ExceptionHandler(UsernameTakenException::class)
    fun usernameTaken(e: UsernameTakenException) =
        respond(HttpStatus.CONFLICT, e.message!!, "EMAIL_TAKEN", listOf(FieldProblem("email", e.message!!)))

    @ExceptionHandler(SkuTakenException::class)
    fun skuTaken(e: SkuTakenException) =
        respond(HttpStatus.CONFLICT, e.message!!, "SKU_TAKEN", listOf(FieldProblem("sku", "SKU ${e.sku} already exists")))

    @ExceptionHandler(StaleProductException::class)
    fun stale(e: StaleProductException) = respond(
        HttpStatus.CONFLICT,
        "Someone changed product ${e.id} after you opened it (a sale or another edit). Reload to see the current values.",
        "STALE_VERSION",
    )

    @ExceptionHandler(ProductNotFoundException::class, NotFoundException::class)
    fun notFound(e: RuntimeException) = respond(HttpStatus.NOT_FOUND, e.message!!)

    @ExceptionHandler(OutOfStockException::class)
    fun outOfStock(e: OutOfStockException) = respond(
        HttpStatus.CONFLICT,
        "Some items are no longer available in that quantity",
        "OUT_OF_STOCK",
        extra = mapOf("productId" to e.productId),
    )

    @ExceptionHandler(PriceChangedException::class)
    fun priceChanged(e: PriceChangedException) = respond(
        HttpStatus.CONFLICT,
        "The order total changed to ${e.newTotal.toPlainString()}",
        "PRICE_CHANGED",
        extra = mapOf("total" to e.newTotal.toPlainString()),
    )

    @ExceptionHandler(IdempotencyKeyReusedException::class)
    fun idempotencyKeyReused(e: IdempotencyKeyReusedException) =
        respond(HttpStatus.UNPROCESSABLE_CONTENT, e.message!!, "IDEMPOTENCY_KEY_REUSED")

    @ExceptionHandler(OrderRefusedException::class)
    fun orderRefused(e: OrderRefusedException) = respond(HttpStatus.BAD_REQUEST, e.message!!, e.code)

    @ExceptionHandler(ImportFileException::class)
    fun importFile(e: ImportFileException) = respond(HttpStatus.BAD_REQUEST, e.message!!, "IMPORT_FILE")

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ProblemDetail> {
        log.error("Unexpected error", e)
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side. Try again later.", "INTERNAL_ERROR")
    }

    private fun respond(
        status: HttpStatus,
        detail: String,
        code: String? = null,
        errors: List<FieldProblem> = emptyList(),
        extra: Map<String, Any> = emptyMap(),
    ): ResponseEntity<ProblemDetail> = ResponseEntity.status(status).body(problem(status, detail, code, errors, extra))

    private fun problem(
        status: HttpStatus,
        detail: String,
        code: String? = null,
        errors: List<FieldProblem> = emptyList(),
        extra: Map<String, Any> = emptyMap(),
    ): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(status, detail)
        code?.let { problem.setProperty("code", it) }
        if (errors.isNotEmpty()) {
            problem.setProperty("errors", errors.map { mapOf("field" to apiField(it.field), "message" to it.message) })
        }
        extra.forEach { (key, value) -> problem.setProperty(key, value) }
        return problem
    }

    private fun apiField(field: String) = if (field == "weight_kg") "weightKg" else field
}

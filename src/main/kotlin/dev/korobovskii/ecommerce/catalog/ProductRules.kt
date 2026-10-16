package dev.korobovskii.ecommerce.catalog

import java.math.BigDecimal

data class FieldProblem(val field: String, val message: String) {
    override fun toString(): String = message
}

sealed interface ProductCheck {
    data class Valid(val draft: ProductDraft, val warnings: List<FieldProblem>) : ProductCheck

    data class Invalid(val problems: List<FieldProblem>) : ProductCheck
}

object ProductRules {
    const val SKU_MAX = 64
    const val NAME_MAX = 200
    const val DESCRIPTION_MAX = 10_000
    const val CATEGORY_MAX = 100
    const val STOCK_MAX = 1_000_000
    private const val QUOTE_MAX = 40
    private const val WEIGHT_SCALE = 3

    private val skuPattern = Regex("[A-Z0-9]+(-[A-Z0-9]+)*")
    private val pricePattern = Regex("\\d{1,10}(\\.\\d{1,2})?")
    private val stockPattern = Regex("-?\\d{1,9}")
    private val weightPattern = Regex("\\d{1,7}(\\.\\d{1,3})?")
    private val controlCharacters = Regex("[\\p{Cntrl}&&[^\\t\\n\\r]]")

    fun check(input: ProductInput): ProductCheck {
        val problems = mutableListOf<FieldProblem>()
        val warnings = mutableListOf<FieldProblem>()

        val sku = input.sku.clean().uppercase()
        when {
            sku.isEmpty() -> problems += FieldProblem("sku", "SKU is required")
            sku.length > SKU_MAX -> problems += FieldProblem("sku", "SKU is longer than $SKU_MAX characters")
            !skuPattern.matches(sku) ->
                problems += FieldProblem("sku", "SKU ${quote(sku)} may contain only letters, digits and single hyphens")
        }

        val name = input.name.clean()
        checkText("name", name, NAME_MAX, required = true, problems)
        val description = input.description.clean()
        checkText("description", description, DESCRIPTION_MAX, required = false, problems)
        val category = input.category.clean()
        checkText("category", category, CATEGORY_MAX, required = true, problems)

        val price = parsePrice(input.price.clean(), problems)
        val stock = parseStock(input.stock.clean(), problems)
        val weight = parseWeight(input.weightKg.clean(), problems, warnings)

        if (problems.isNotEmpty() || price == null || stock == null) {
            return ProductCheck.Invalid(problems)
        }
        val draft = ProductDraft(
            sku = sku,
            name = name,
            description = description.ifEmpty { null },
            category = category,
            price = price,
            stock = stock,
            weightKg = weight,
        )
        return ProductCheck.Valid(draft, warnings)
    }

    private fun checkText(field: String, value: String, max: Int, required: Boolean, problems: MutableList<FieldProblem>) {
        val label = field.replaceFirstChar { it.uppercase() }
        when {
            required && value.isEmpty() -> problems += FieldProblem(field, "$label is required")
            value.length > max -> problems += FieldProblem(field, "$label is longer than $max characters")
            controlCharacters.containsMatchIn(value) -> problems += FieldProblem(field, "$label contains control characters")
        }
    }

    private fun parsePrice(text: String, problems: MutableList<FieldProblem>): BigDecimal? {
        if (text.isEmpty()) {
            problems += FieldProblem("price", "Price is required")
            return null
        }
        if (!pricePattern.matches(text)) {
            problems += FieldProblem("price", "Price ${quote(text)} is not a plain decimal such as 19.99")
            return null
        }
        val value = BigDecimal(text).setScale(2)
        if (value.signum() <= 0) {
            problems += FieldProblem("price", "Price must be greater than 0")
            return null
        }
        return value
    }

    private fun parseStock(text: String, problems: MutableList<FieldProblem>): Int? {
        if (text.isEmpty()) {
            problems += FieldProblem("stock", "Stock is required")
            return null
        }
        if (!stockPattern.matches(text)) {
            problems += FieldProblem("stock", "Stock ${quote(text)} is not a whole number")
            return null
        }
        val value = text.toInt()
        return when {
            value < 0 -> {
                problems += FieldProblem("stock", "Stock cannot be negative ($value)")
                null
            }
            value > STOCK_MAX -> {
                problems += FieldProblem("stock", "Stock is above $STOCK_MAX")
                null
            }
            else -> value
        }
    }

    private fun parseWeight(
        text: String,
        problems: MutableList<FieldProblem>,
        warnings: MutableList<FieldProblem>,
    ): BigDecimal? {
        if (text.isEmpty()) {
            warnings += FieldProblem("weight_kg", "Weight is empty; the product is saved without a weight")
            return null
        }
        if (!weightPattern.matches(text)) {
            problems += FieldProblem("weight_kg", "Weight ${quote(text)} is not a decimal number such as 0.25")
            return null
        }
        return BigDecimal(text).setScale(WEIGHT_SCALE)
    }

    private fun String?.clean(): String = this?.trim().orEmpty()

    private fun quote(value: String): String =
        "\"" + (if (value.length > QUOTE_MAX) value.take(QUOTE_MAX) + "…" else value) + "\""
}

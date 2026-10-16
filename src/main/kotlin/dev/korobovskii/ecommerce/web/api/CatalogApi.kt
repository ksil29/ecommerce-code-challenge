package dev.korobovskii.ecommerce.web.api

import dev.korobovskii.ecommerce.audit.AuditLog
import dev.korobovskii.ecommerce.catalog.FieldProblem
import dev.korobovskii.ecommerce.catalog.ProductCheck
import dev.korobovskii.ecommerce.catalog.ProductDraft
import dev.korobovskii.ecommerce.catalog.ProductNotFoundException
import dev.korobovskii.ecommerce.catalog.ProductRules
import dev.korobovskii.ecommerce.catalog.ProductSearch
import dev.korobovskii.ecommerce.catalog.ProductService
import dev.korobovskii.ecommerce.catalog.importing.ImportFileException
import dev.korobovskii.ecommerce.catalog.importing.ImportReport
import dev.korobovskii.ecommerce.catalog.importing.ProductImporter
import dev.korobovskii.ecommerce.ordering.OrderRepository
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.net.URI

@RestController
@RequestMapping("/api")
class CatalogApi(private val products: ProductService) {

    @GetMapping("/products")
    fun search(@Valid query: ProductQuery, authentication: Authentication?): PageDto<ProductDto> {
        val search = ProductSearch(
            text = query.q?.trim()?.take(200)?.ifEmpty { null },
            categoryId = query.categoryId,
            minPrice = query.minPrice,
            maxPrice = query.maxPrice,
            inStockOnly = query.inStock,
            includeArchived = query.includeArchived && authentication.isAdmin(),
            sort = query.sort,
            page = query.page,
            size = query.size,
        )
        return PageDto.of(products.search(search), ProductDto::of)
    }

    @GetMapping("/products/{sku}")
    fun product(@PathVariable sku: String): ProductDto =
        ProductDto.of(products.findActiveBySku(sku) ?: throw ProductNotFoundException(sku))

    @GetMapping("/categories")
    fun categories(): List<CategoryDto> = products.categories().map { CategoryDto(it.id, it.name) }
}

@RestController
@RequestMapping("/api/admin")
class AdminApi(
    private val products: ProductService,
    private val importer: ProductImporter,
    private val orders: OrderRepository,
    private val audit: AuditLog,
) {

    @GetMapping("/products/{id}")
    fun product(@PathVariable id: Long): ProductDto = ProductDto.of(products.get(id))

    @PostMapping("/products")
    fun create(@RequestBody request: ProductRequest): ResponseEntity<ProductDto> {
        val id = products.create(validate(request))
        return ResponseEntity.created(URI.create("/api/admin/products/$id")).body(ProductDto.of(products.get(id)))
    }

    @PutMapping("/products/{id}")
    fun update(@PathVariable id: Long, @RequestBody request: ProductRequest): ProductDto {
        val draft = validate(request)
        val version = request.version
            ?: throw InvalidRequestException(listOf(FieldProblem("version", "Version is required")))
        products.update(id, version, draft)
        return ProductDto.of(products.get(id))
    }

    @PostMapping("/products/{id}/archive")
    fun archive(@PathVariable id: Long, @RequestBody request: VersionRequest): ProductDto {
        products.archive(id, request.version)
        return ProductDto.of(products.get(id))
    }

    @PostMapping("/products/{id}/restore")
    fun restore(@PathVariable id: Long, @RequestBody request: VersionRequest): ProductDto {
        products.restore(id, request.version)
        return ProductDto.of(products.get(id))
    }

    @PostMapping("/imports", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun importCsv(@RequestParam("file") file: MultipartFile): ImportReport {
        if (file.isEmpty) throw ImportFileException("Choose a CSV file to import")
        return importer.import(file.originalFilename ?: "upload.csv", file.bytes)
    }

    @GetMapping("/orders")
    fun orders(@RequestParam(defaultValue = "100") limit: Int): List<OrderSummaryDto> =
        orders.recent(limit.coerceIn(1, MAX_LIST)).map(OrderSummaryDto::of)

    @GetMapping("/audit")
    fun audit(
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(required = false) entityType: String?,
        @RequestParam(required = false) entityId: String?,
    ): List<AuditEventDto> =
        audit.recent(limit.coerceIn(1, MAX_LIST), entityType?.ifBlank { null }, entityId?.ifBlank { null }).map {
            AuditEventDto(it.id, it.occurredAt, it.actor, it.action, it.entityType, it.entityId, it.details)
        }

    private fun validate(request: ProductRequest): ProductDraft =
        when (val check = ProductRules.check(request.toInput())) {
            is ProductCheck.Valid -> check.draft
            is ProductCheck.Invalid -> throw InvalidRequestException(check.problems, "The product is not valid")
        }
}

private const val MAX_LIST = 500

fun Authentication?.isAdmin(): Boolean =
    this != null && this !is AnonymousAuthenticationToken && authorities.any { it.authority == "ROLE_ADMIN" }

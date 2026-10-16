package dev.korobovskii.ecommerce.catalog.importing

import dev.korobovskii.ecommerce.catalog.ProductRepository
import dev.korobovskii.ecommerce.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["app.sample-data.enabled"], havingValue = "true")
class SampleDataLoader(
    private val importer: ProductImporter,
    private val products: ProductRepository,
    private val resources: ResourceLoader,
    private val properties: AppProperties,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (products.count() > 0) {
            log.info("Catalog is not empty; sample data skipped")
            return
        }
        val resource = resources.getResource(properties.sampleData.location)
        val bytes = resource.inputStream.use { it.readBytes() }
        importer.import(resource.filename ?: "sample.csv", bytes)
    }
}

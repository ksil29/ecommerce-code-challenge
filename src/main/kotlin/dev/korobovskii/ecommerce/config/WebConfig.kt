package dev.korobovskii.ecommerce.config

import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver
import java.time.Duration

@Configuration
class WebConfig : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/assets/**")
            .addResourceLocations("classpath:/static/assets/")
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(ASSET_CACHE_DAYS)).cachePublic().immutable())
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .setCacheControl(CacheControl.noCache())
            .resourceChain(false)
            .addResolver(SpaFallbackResolver())
    }
}

private const val ASSET_CACHE_DAYS = 365L

class SpaFallbackResolver : PathResourceResolver() {

    override fun getResource(resourcePath: String, location: Resource): Resource? {
        super.getResource(resourcePath, location)?.let { return it }
        val isServerPath = resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")
        val looksLikeFile = resourcePath.substringAfterLast('/').contains('.')
        return if (isServerPath || looksLikeFile) null else super.getResource("index.html", location)
    }
}

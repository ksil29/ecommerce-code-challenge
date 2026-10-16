package dev.korobovskii.ecommerce.config

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.env.StandardEnvironment
import kotlin.test.assertEquals

class ConfigurationProfilesTest {

    private val localNetworks = "192.168.0.0/16, 172.16.0.0/12, 169.254.0.0/16, fc00::/7, 10.0.0.0/8, " +
        "100.64.0.0/10, 127.0.0.0/8, fe80::/10, ::1/128"

    private val keys = listOf(
        "spring.datasource.url",
        "spring.datasource.username",
        "spring.datasource.password",
        "app.admin.password",
        "app.admin.allow-weak-password",
        "app.sample-data.enabled",
        "server.servlet.session.cookie.secure",
        "server.tomcat.remoteip.internal-proxies",
        "springdoc.api-docs.enabled",
        "springdoc.swagger-ui.enabled",
    )

    private fun settings(vararg profiles: String): Map<String, String?> {
        val context = GenericApplicationContext()
        context.environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        context.environment.setActiveProfiles(*profiles)
        ConfigDataApplicationContextInitializer().initialize(context)
        return keys.associateWith { context.environment.getProperty(it) }
    }

    @Test
    fun `the base configuration holds no credentials, trusts no proxy, needs HTTPS for the cookie and hides the API docs`() {
        assertEquals(
            mapOf(
                "spring.datasource.url" to "",
                "spring.datasource.username" to "",
                "spring.datasource.password" to "",
                "app.admin.password" to "",
                "app.admin.allow-weak-password" to null,
                "app.sample-data.enabled" to "false",
                "server.servlet.session.cookie.secure" to "true",
                "server.tomcat.remoteip.internal-proxies" to "",
                "springdoc.api-docs.enabled" to "false",
                "springdoc.swagger-ui.enabled" to "false",
            ),
            settings(),
        )
    }

    @Test
    fun `the local profile adds the demo database, admin, catalog, API docs, local proxies and a cookie for plain HTTP`() {
        assertEquals(
            mapOf(
                "spring.datasource.url" to "jdbc:postgresql://localhost:5432/shop",
                "spring.datasource.username" to "shop",
                "spring.datasource.password" to "shop",
                "app.admin.password" to "admin",
                "app.admin.allow-weak-password" to "true",
                "app.sample-data.enabled" to "true",
                "server.servlet.session.cookie.secure" to "false",
                "server.tomcat.remoteip.internal-proxies" to localNetworks,
                "springdoc.api-docs.enabled" to "true",
                "springdoc.swagger-ui.enabled" to "true",
            ),
            settings("local"),
        )
    }
}

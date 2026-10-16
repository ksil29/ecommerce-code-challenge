package dev.korobovskii.ecommerce.config

import dev.korobovskii.ecommerce.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.context.annotation.Import
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

abstract class ForwardedAddressTest {

    @Value("\${local.server.port}")
    private var port = 0

    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    protected fun settingsFrom(vararg addresses: String): List<Int> = addresses.map { address ->
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/api/settings"))
            .header("X-Forwarded-For", address)
            .GET()
            .build()
        http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }
}

@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = ["app.admin.password=test-password", "app.rate-limit.enabled=true", "app.rate-limit.api.requests=2"],
)
@Import(TestcontainersConfiguration::class)
class UntrustedPeerForwardedAddressTest : ForwardedAddressTest() {

    @Test
    fun `X-Forwarded-For from a peer that is not a trusted proxy is ignored, so a new address does not dodge the limit`() {
        assertEquals(listOf(200, 200, 429), settingsFrom("203.0.113.1", "203.0.113.2", "203.0.113.3"))
    }
}

@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = [
        "app.admin.password=test-password",
        "app.rate-limit.enabled=true",
        "app.rate-limit.api.requests=2",
        "server.tomcat.remoteip.internal-proxies=127.0.0.0/8, ::1/128",
    ],
)
@Import(TestcontainersConfiguration::class)
class TrustedProxyForwardedAddressTest : ForwardedAddressTest() {

    @Test
    fun `behind a trusted proxy each client address gets its own limit`() {
        assertEquals(
            listOf(200, 200, 200, 200, 429),
            settingsFrom("203.0.113.1", "203.0.113.2", "203.0.113.3", "203.0.113.3", "203.0.113.3"),
        )
    }
}

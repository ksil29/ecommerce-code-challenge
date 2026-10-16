package dev.korobovskii.ecommerce.web.api

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
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = ["app.admin.password=test-password", "app.rate-limit.enabled=false"],
)
@Import(TestcontainersConfiguration::class)
class SessionCookieTest {

    @Value("\${local.server.port}")
    private var port = 0

    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    @Test
    fun `the running server sends the session cookie as Secure, HttpOnly and SameSite=Lax`() {
        val xsrf = send(request("/api/auth/me").GET()).cookie("XSRF-TOKEN").substringAfter('=').substringBefore(';')
        val login = send(
            request("/api/auth/login")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", "XSRF-TOKEN=$xsrf")
                .header("X-XSRF-TOKEN", xsrf)
                .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=test-password")),
        )

        assertEquals(204, login.statusCode())
        val session = login.cookie("SESSION")
        val attributes = session.split(';').drop(1).map { it.trim() }
        assertTrue(attributes.containsAll(listOf("Secure", "HttpOnly", "SameSite=Lax")), session)
    }

    @Test
    fun `a request that is refused for lack of a login opens no session`() {
        val response = send(request("/api/admin/orders").GET())

        assertEquals(401, response.statusCode())
        assertTrue(response.headers().allValues("Set-Cookie").none { it.startsWith("SESSION=") })
    }

    private fun request(path: String) = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))

    private fun send(request: HttpRequest.Builder) = http.send(request.build(), HttpResponse.BodyHandlers.discarding())

    private fun HttpResponse<*>.cookie(name: String): String =
        headers().allValues("Set-Cookie").single { it.startsWith("$name=") }
}

package dev.korobovskii.ecommerce.web.api

import dev.korobovskii.ecommerce.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.net.URI

@SpringBootTest(
    properties = [
        "app.admin.password=test-password",
        "app.rate-limit.enabled=true",
        "app.rate-limit.login.requests=3",
        "app.rate-limit.login.per=PT1M",
        "app.rate-limit.api.requests=5",
        "app.rate-limit.api.per=PT1M",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class RateLimitTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    private fun from(address: String) = RequestPostProcessor { request ->
        request.remoteAddr = address
        request
    }

    private fun login(address: String, path: String = "/api/auth/login") =
        mockMvc.post(URI.create(path)) {
            with(csrf())
            with(from(address))
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            param("username", "admin")
            param("password", "a wrong password")
        }

    @Test
    fun `the fourth login attempt from one address in a minute is refused with 429`() {
        repeat(3) { login("10.0.0.1").andExpect { status { isUnauthorized() } } }

        login("10.0.0.1").andExpect {
            status { isTooManyRequests() }
            header { exists("Retry-After") }
            jsonPath("$.code") { value("RATE_LIMITED") }
        }
        login("10.0.0.2").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a percent-encoded path counts against the same sign-in limit`() {
        repeat(3) { login("10.0.0.6", "/%61pi/auth/login").andExpect { status { isUnauthorized() } } }

        login("10.0.0.6", "/%61pi/auth/%6Cogin").andExpect { status { isTooManyRequests() } }
        login("10.0.0.6").andExpect { status { isTooManyRequests() } }
    }

    @Test
    fun `other API calls have their own budget`() {
        repeat(5) { mockMvc.get("/api/categories") { with(from("10.0.0.3")) }.andExpect { status { isOk() } } }

        mockMvc.get("/api/categories") { with(from("10.0.0.3")) }.andExpect { status { isTooManyRequests() } }
        mockMvc.get("/api/categories") { with(from("10.0.0.4")) }.andExpect { status { isOk() } }
    }

    @Test
    fun `pages and static files are not limited`() {
        repeat(10) { mockMvc.get("/login") { with(from("10.0.0.5")) } }
        mockMvc.get("/api/categories") { with(from("10.0.0.5")) }.andExpect { status { isOk() } }
    }
}

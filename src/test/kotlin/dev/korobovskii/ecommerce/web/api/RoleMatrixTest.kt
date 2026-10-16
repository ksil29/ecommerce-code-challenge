package dev.korobovskii.ecommerce.web.api

import dev.korobovskii.ecommerce.IntegrationTest
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.request.RequestPostProcessor
import kotlin.test.assertEquals

class RoleMatrixTest : IntegrationTest() {

    @Autowired
    lateinit var mockMvc: MockMvc

    private data class Case(
        val method: HttpMethod,
        val path: String,
        val body: String?,
        val anonymous: Int,
        val customer: Int,
        val admin: Int,
    )

    private val cases = listOf(
        Case(HttpMethod.GET, "/api/products", null, 200, 200, 200),
        Case(HttpMethod.GET, "/api/products/NO-SUCH-SKU", null, 404, 404, 404),
        Case(HttpMethod.GET, "/api/categories", null, 200, 200, 200),
        Case(HttpMethod.GET, "/api/settings", null, 200, 200, 200),
        Case(HttpMethod.GET, "/api/auth/me", null, 200, 200, 200),
        Case(HttpMethod.POST, "/api/auth/register", "{}", 400, 400, 400),
        Case(HttpMethod.POST, "/api/auth/login", null, 401, 401, 401),
        Case(HttpMethod.POST, "/api/auth/logout", null, 204, 204, 204),
        Case(HttpMethod.POST, "/api/cart/quote", """{"items":[]}""", 200, 200, 200),
        Case(HttpMethod.POST, "/api/orders", "{}", 400, 400, 400),
        Case(HttpMethod.GET, "/api/orders/00000000-0000-0000-0000-000000000000", null, 404, 404, 404),
        Case(HttpMethod.GET, "/api/me/orders", null, 401, 200, 200),
        Case(HttpMethod.GET, "/api/admin/orders", null, 401, 403, 200),
        Case(HttpMethod.GET, "/api/admin/audit", null, 401, 403, 200),
        Case(HttpMethod.GET, "/api/admin/products/999", null, 401, 403, 404),
        Case(HttpMethod.POST, "/api/admin/products", "{}", 401, 403, 400),
        Case(HttpMethod.PUT, "/api/admin/products/999", "{}", 401, 403, 400),
        Case(HttpMethod.POST, "/api/admin/products/999/archive", """{"version":0}""", 401, 403, 404),
        Case(HttpMethod.POST, "/api/admin/products/999/restore", """{"version":0}""", 401, 403, 404),
    )

    private val roles: List<Pair<String, RequestPostProcessor?>> = listOf(
        "anonymous" to null,
        "customer" to user("grace@example.com").roles("CUSTOMER"),
        "admin" to user("admin").roles("ADMIN"),
    )

    @TestFactory
    fun `each endpoint answers each role as designed`(): List<DynamicTest> =
        cases.flatMap { case ->
            roles.map { (role, identity) ->
                DynamicTest.dynamicTest("${case.method} ${case.path} as $role") {
                    val builder = request(case.method, case.path).with(csrf())
                    identity?.let { builder.with(it) }
                    case.body?.let { builder.contentType(MediaType.APPLICATION_JSON).content(it) }
                    val expected = when (role) {
                        "anonymous" -> case.anonymous
                        "customer" -> case.customer
                        else -> case.admin
                    }
                    assertEquals(expected, mockMvc.perform(builder).andReturn().response.status)
                }
            }
        }

    @TestFactory
    fun `only the admin can import`(): List<DynamicTest> =
        roles.map { (role, identity) ->
            DynamicTest.dynamicTest("POST /api/admin/imports as $role") {
                val builder = multipart("/api/admin/imports")
                    .file(MockMultipartFile("file", "empty.csv", "text/csv", ByteArray(0)))
                    .with(csrf())
                identity?.let { builder.with(it) }
                val expected = mapOf("anonymous" to 401, "customer" to 403, "admin" to 400).getValue(role)
                assertEquals(expected, mockMvc.perform(builder).andReturn().response.status)
            }
        }
}

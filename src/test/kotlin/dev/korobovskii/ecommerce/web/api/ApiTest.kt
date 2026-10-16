package dev.korobovskii.ecommerce.web.api

import dev.korobovskii.ecommerce.IntegrationTest
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.UUID
import kotlin.test.assertEquals

class ApiTest : IntegrationTest() {

    @Autowired
    lateinit var mockMvc: MockMvc

    private val admin = user("admin").roles("ADMIN")

    private fun orderJson(productId: Long, quantity: Int, expectedTotal: String, card: String = "4242 4242 4242 4242") = """
        {"items":[{"productId":$productId,"quantity":$quantity}],"expectedTotal":"$expectedTotal",
         "customerName":"Ada Buyer","customerEmail":"ada@example.com","cardNumber":"$card"}
    """.trimIndent()

    @Test
    fun `admin endpoints answer 401 without a login`() {
        mockMvc.get("/api/admin/orders").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `the admin signs in through the API with a CSRF token and a database session`() {
        mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            param("username", "admin")
            param("password", "test-password")
        }.andExpect { status { isForbidden() } }
        mockMvc.post("/api/auth/login") {
            with(csrf())
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            param("username", "admin")
            param("password", "wrong")
        }.andExpect { status { isUnauthorized() } }
        val session = mockMvc.post("/api/auth/login") {
            with(csrf())
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            param("username", "admin")
            param("password", "test-password")
        }.andExpect { status { isNoContent() } }.andReturn().response.getCookie("SESSION")!!

        mockMvc.get("/api/auth/me") { cookie(session) }.andExpect {
            status { isOk() }
            jsonPath("$.admin") { value(true) }
            jsonPath("$.username") { value("admin") }
        }
        val sessions = jdbc.sql("SELECT count(*) FROM spring_session WHERE principal_name = 'admin'")
            .query(Int::class.javaObjectType)
            .single()
        assertEquals(1, sessions)

        mockMvc.post("/api/auth/logout") {
            with(csrf())
            cookie(session)
        }.andExpect { status { isNoContent() } }
        mockMvc.get("/api/auth/me") { cookie(session) }.andExpect { jsonPath("$.authenticated") { value(false) } }
    }

    @Test
    fun `an order can be read back by its id, and admins see the order list`() {
        val id = createProduct(price = "2.50", stock = 10)
        val created = mockMvc.post("/api/orders") {
            with(csrf())
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType = MediaType.APPLICATION_JSON
            content = orderJson(id, 1, "2.50")
        }.andReturn().response
        val orderId = Regex("\"id\":\"([^\"]+)\"").find(created.contentAsString)!!.groupValues[1]

        mockMvc.get("/api/orders/$orderId").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("PAID") }
            jsonPath("$.lines[0].quantity") { value(1) }
        }
        mockMvc.get("/api/orders/${UUID.randomUUID()}").andExpect { status { isNotFound() } }
        mockMvc.get("/api/admin/orders") { with(admin) }.andExpect {
            status { isOk() }
            jsonPath("$[0].id") { value(orderId) }
            jsonPath("$[0].lines") { doesNotExist() }
        }
    }

    @Test
    fun `archive and restore through the API`() {
        val id = createProduct(sku = "PEN-1")
        mockMvc.post("/api/admin/products/$id/archive") {
            with(csrf())
            with(admin)
            contentType = MediaType.APPLICATION_JSON
            content = """{"version":0}"""
        }.andExpect { jsonPath("$.archived") { value(true) } }
        mockMvc.get("/api/products/PEN-1").andExpect { status { isNotFound() } }
        mockMvc.post("/api/admin/products/$id/restore") {
            with(csrf())
            with(admin)
            contentType = MediaType.APPLICATION_JSON
            content = """{"version":1}"""
        }.andExpect { jsonPath("$.archived") { value(false) } }
        mockMvc.get("/api/products/PEN-1").andExpect { status { isOk() } }
    }

    @Test
    fun `responses carry the content security policy`() {
        mockMvc.get("/api/categories").andExpect {
            status { isOk() }
            header { string("Content-Security-Policy", containsString("default-src 'self'")) }
        }
    }

    @Test
    fun `a product is validated by the same rules as a CSV row`() {
        mockMvc.post("/api/admin/products") {
            with(csrf())
            with(admin)
            contentType = MediaType.APPLICATION_JSON
            content = """{"sku":"pen-1","name":" ","category":"Office","price":"free","stock":"-5","weightKg":""}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[*].field") { value(contains("name", "price", "stock")) }
        }

        mockMvc.post("/api/admin/products") {
            with(csrf())
            with(admin)
            contentType = MediaType.APPLICATION_JSON
            content = """{"sku":"pen-1","name":"Pen","category":"Office","price":"1.50","stock":"10","weightKg":"0.02"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.sku") { value("PEN-1") }
            jsonPath("$.price") { value("1.50") }
            jsonPath("$.version") { value(0) }
        }
    }

    @Test
    fun `an update from a stale version answers 409`() {
        val id = createProduct(sku = "PEN-1", stock = 10)
        val body = """{"sku":"PEN-1","name":"Pen","category":"Office","price":"1.50","stock":"12","weightKg":"","version":0}"""
        mockMvc.put("/api/admin/products/$id") {
            with(csrf())
            with(admin)
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isOk() } }

        mockMvc.put("/api/admin/products/$id") {
            with(csrf())
            with(admin)
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("STALE_VERSION") }
        }
    }

    @Test
    fun `the import endpoint returns the report`() {
        mockMvc.multipart("/api/admin/imports") {
            file("file", sampleCsv())
            with(csrf())
            with(admin)
        }.andExpect {
            status { isOk() }
            jsonPath("$.created") { value(85) }
            jsonPath("$.rejected") { value(7) }
            jsonPath("$.issues[0].line") { value(4) }
            jsonPath("$.issues[0].kind") { value("REJECTED") }
        }
    }

    @Test
    fun `ordering is idempotent per key`() {
        val id = createProduct(price = "2.50", stock = 10)
        val key = UUID.randomUUID().toString()
        val first = mockMvc.post("/api/orders") {
            with(csrf())
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = orderJson(id, 2, "5.00")
        }.andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("PAID") }
        }.andReturn().response.contentAsString

        val second = mockMvc.post("/api/orders") {
            with(csrf())
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = orderJson(id, 2, "5.00")
        }.andReturn().response.contentAsString

        assertEquals(first, second)
        assertEquals(8, stockOf(id))
    }

    @Test
    fun `order errors come back as problem details`() {
        val id = createProduct(price = "2.50", stock = 1)
        mockMvc.post("/api/orders") {
            with(csrf())
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType = MediaType.APPLICATION_JSON
            content = orderJson(id, 1, "1.00")
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("PRICE_CHANGED") }
            jsonPath("$.total") { value("2.50") }
        }
        mockMvc.post("/api/orders") {
            with(csrf())
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType = MediaType.APPLICATION_JSON
            content = orderJson(id, 2, "5.00")
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("OUT_OF_STOCK") }
        }
        mockMvc.post("/api/orders") {
            with(csrf())
            header("Idempotency-Key", "not-a-uuid")
            contentType = MediaType.APPLICATION_JSON
            content = orderJson(id, 1, "2.50", card = "1234")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[*].field") { value(contains("idempotencyKey", "cardNumber")) }
        }
        mockMvc.post("/api/orders") {
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType = MediaType.APPLICATION_JSON
            content = orderJson(id, 1, "2.50")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `the quote prices the cart from the database`() {
        val pen = createProduct(sku = "PEN-1", price = "2.50", stock = 1)
        mockMvc.post("/api/cart/quote") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"items":[{"productId":$pen,"quantity":2},{"productId":999,"quantity":1}]}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value("5.00") }
            jsonPath("$.lines[0].available") { value(false) }
            jsonPath("$.missingProductIds[0]") { value(999) }
            jsonPath("$.canCheckout") { value(false) }
        }
    }

    @Test
    fun `the public catalog never shows archived products`() {
        val hidden = createProduct(sku = "OLD-1", name = "Old pen")
        createProduct(sku = "NEW-1", name = "New pen")
        productService.archive(hidden, 0)

        mockMvc.get("/api/products") { param("includeArchived", "true") }.andExpect {
            status { isOk() }
            jsonPath("$.items[*].sku") { value(not(hasItem("OLD-1"))) }
        }
        mockMvc.get("/api/products") {
            param("includeArchived", "true")
            with(admin)
        }.andExpect { jsonPath("$.items[*].sku") { value(hasItem("OLD-1")) } }
        mockMvc.get("/api/products/OLD-1").andExpect { status { isNotFound() } }
    }

    @Test
    fun `bad paging parameters are rejected`() {
        mockMvc.get("/api/products") { param("size", "1000") }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a bad search parameter is rejected without naming internal types`() {
        mockMvc.get("/api/products") { param("sort", "cheapest") }.andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("sort") }
            jsonPath("$.errors[0].message") { value("This value is not valid") }
        }
        mockMvc.get("/api/products") { param("q", "pen\u0000") }.andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("q") }
        }
    }

    @Test
    fun `quantities outside 1 to the configured maximum are rejected by the quote and the order`() {
        val pen = createProduct(price = "2.50", stock = 500)
        for (quantity in listOf(-3, 0, 100)) {
            mockMvc.post("/api/cart/quote") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = """{"items":[{"productId":$pen,"quantity":$quantity}]}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.errors[0].field") { value("items[0].quantity") }
                jsonPath("$.errors[0].message") { value("Choose a quantity from 1 to 99") }
            }
        }
        mockMvc.post("/api/orders") {
            with(csrf())
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType = MediaType.APPLICATION_JSON
            content = orderJson(pen, -1, "-2.50")
        }.andExpect { status { isBadRequest() } }
        assertEquals(500, stockOf(pen))
    }

    @Test
    fun `a product split over several lines counts against the quantity limit as a whole`() {
        val pen = createProduct(price = "2.50", stock = 500)
        mockMvc.post("/api/cart/quote") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"items":[{"productId":$pen,"quantity":60},{"productId":$pen,"quantity":40}]}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("items[0].quantity") }
            jsonPath("$.errors[0].message") { value("Choose up to 99 of one product in total") }
            jsonPath("$.errors[1].field") { value("items[1].quantity") }
        }
    }

    @Test
    fun `the shop settings the browser needs are public`() {
        mockMvc.get("/api/settings").andExpect {
            status { isOk() }
            jsonPath("$.maxQuantity") { value(99) }
            jsonPath("$.paymentTimeoutSeconds") { value(900) }
            jsonPath("$.reconcileIntervalSeconds") { value(60) }
            jsonPath("$.apiDocs") { value(false) }
        }
    }

    @Test
    fun `a key sent again with a different cart gets 422`() {
        val pen = createProduct(price = "2.50", stock = 10)
        val key = UUID.randomUUID().toString()
        val send = { quantity: Int, total: String ->
            mockMvc.post("/api/orders") {
                with(csrf())
                header("Idempotency-Key", key)
                contentType = MediaType.APPLICATION_JSON
                content = orderJson(pen, quantity, total)
            }
        }

        send(1, "2.50").andExpect { status { isCreated() } }
        send(1, "2.50").andExpect { status { isCreated() } }
        send(2, "5.00").andExpect {
            status { isEqualTo(422) }
            jsonPath("$.code") { value("IDEMPOTENCY_KEY_REUSED") }
        }
        assertEquals(9, stockOf(pen))
    }
}

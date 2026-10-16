package shop

import io.gatling.javaapi.core.CoreDsl.StringBody
import io.gatling.javaapi.core.CoreDsl.constantUsersPerSec
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.global
import io.gatling.javaapi.core.CoreDsl.jsonPath
import io.gatling.javaapi.core.CoreDsl.listFeeder
import io.gatling.javaapi.core.CoreDsl.rampUsersPerSec
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.CookieKey
import io.gatling.javaapi.http.HttpDsl.getCookieValue
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import java.time.Duration
import java.util.UUID

class ShopSimulation : Simulation() {

    private val baseUrl = System.getenv("BASE_URL") ?: "http://localhost:8080"
    private val peakBrowsersPerSecond = System.getenv("BROWSERS_PER_SEC")?.toDouble() ?: 100.0
    private val buyersPerSecond = System.getenv("BUYERS_PER_SEC")?.toDouble() ?: 20.0
    private val duration = Duration.ofSeconds(System.getenv("DURATION_SECONDS")?.toLong() ?: 60L)

    private val protocol = http.baseUrl(baseUrl)
        .acceptHeader("application/json")
        .contentTypeHeader("application/json")

    private val terms = listFeeder(
        listOf("speaker", "shoes", "yoga", "bag", "lamp", "coffee", "case", "kit", "steel", "wireless").map { mapOf("term" to it) },
    ).random()

    private val skus = listFeeder(
        listOf("TS-041", "NB-100", "PC-001", "RB-036", "MP-044", "BT-033", "NC-027", "UH-031").map { mapOf("sku" to it) },
    ).random()

    private val browse = scenario("Browse the catalog")
        .feed(terms)
        .exec(
            http("search")
                .get("/api/products?q=#{term}&size=20")
                .check(status().`is`(200), jsonPath("$.items[0].sku").optional().saveAs("found")),
        )
        .doIf { session -> session.contains("found") }
        .then(exec(http("product page").get("/api/products/#{found}").check(status().`is`(200))))

    private val buy = scenario("Buy one item")
        .feed(skus)
        .exec(http("session").get("/api/auth/me").check(status().`is`(200)))
        .exec(getCookieValue(CookieKey("XSRF-TOKEN").saveAs("xsrf")))
        .exec(
            http("product")
                .get("/api/products/#{sku}")
                .check(status().`is`(200), jsonPath("$.id").saveAs("productId")),
        )
        .exec(
            http("price the cart")
                .post("/api/cart/quote")
                .header("X-XSRF-TOKEN", "#{xsrf}")
                .body(StringBody("""{"items":[{"productId":#{productId},"quantity":1}]}"""))
                .check(status().`is`(200), jsonPath("$.total").saveAs("total")),
        )
        .exec { session -> session.set("key", UUID.randomUUID().toString()) }
        .exec(
            http("place the order")
                .post("/api/orders")
                .header("X-XSRF-TOKEN", "#{xsrf}")
                .header("Idempotency-Key", "#{key}")
                .body(
                    StringBody(
                        """{"items":[{"productId":#{productId},"quantity":1}],"expectedTotal":"#{total}",""" +
                            """"customerName":"Load Test","customerEmail":"load@example.com","cardNumber":"4242424242424242"}""",
                    ),
                )
                .check(status().`in`(201, 409)),
        )

    init {
        setUp(
            browse.injectOpen(rampUsersPerSec(1.0).to(peakBrowsersPerSecond).during(duration)),
            buy.injectOpen(constantUsersPerSec(buyersPerSecond).during(duration)),
        ).protocols(protocol)
            .assertions(
                global().responseTime().percentile(95.0).lt(500),
                global().failedRequests().percent().lt(1.0),
            )
    }
}

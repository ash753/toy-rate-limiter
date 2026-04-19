package com.ratelimiter.proxy

import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.junit5.WireMockTest
import com.ratelimiter.common.RateLimitConstants
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "proxy.routes[0].path-pattern=/api/foo/**",
        "proxy.routes[0].target-uri=http://localhost:8089",
        "proxy.routes[0].limit=100",
        "proxy.routes[0].per-ip=false",
        "proxy.routes[1].path-pattern=/api/bar/**",
        "proxy.routes[1].target-uri=http://localhost:8089",
        "proxy.routes[1].limit=50",
        "proxy.routes[1].per-ip=true",
        "proxy.routes[2].path-pattern=/api/**",
        "proxy.routes[2].target-uri=http://localhost:8089"
    ]
)
@Testcontainers
@WireMockTest(httpPort = 8089)
class RateLimitFilterIntegrationTest {

    lateinit var webTestClient: WebTestClient

    @Autowired
    lateinit var context: ApplicationContext

    @Autowired
    lateinit var redisTemplate: ReactiveStringRedisTemplate

    companion object {
        @Container
        val redis = GenericContainer("redis:7-alpine").withExposedPorts(6379)

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
        }
    }

    @BeforeEach
    fun setUp() {
        // Manually build WebTestClient from context
        webTestClient = WebTestClient.bindToApplicationContext(context).build()

        // Clear Redis
        redisTemplate.connectionFactory.reactiveConnection.serverCommands().flushAll().block()
        
        // Mock backend
        stubFor(get(urlMatching("/api/.*"))
            .willReturn(aResponse()
                .withStatus(HttpStatus.OK.value())
                .withHeader("Content-Type", "application/json")
                .withBody("""{"message":"success"}""")))
    }

    @Test
    fun `should allow request and return rate limit headers`() {
        webTestClient.get().uri("/api/foo/test")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals(RateLimitConstants.HEADER_LIMIT, "100")
            .expectHeader().exists(RateLimitConstants.HEADER_REMAINING)
    }

    @Test
    fun `should decrement remaining count on successive requests`() {
        // /api/bar has limit 50 in application-local.yaml
        repeat(5) {
            webTestClient.get().uri("/api/bar/test")
                .header("X-Forwarded-For", "1.1.1.1")
                .exchange()
                .expectStatus().isOk
                .expectHeader().valueEquals(RateLimitConstants.HEADER_LIMIT, "50")
        }

        // Check if remaining decreased
        webTestClient.get().uri("/api/bar/test")
            .header("X-Forwarded-For", "1.1.1.1")
            .exchange()
            .expectHeader().valueEquals(RateLimitConstants.HEADER_REMAINING, "44")
    }

    @Test
    fun `should return 429 too many requests when limit is exceeded`() {
        val limit = 50
        repeat(limit) {
            webTestClient.get().uri("/api/bar/429-test")
                .header("X-Forwarded-For", "2.2.2.2")
                .exchange()
                .expectStatus().isOk
        }

        webTestClient.get().uri("/api/bar/429-test")
            .header("X-Forwarded-For", "2.2.2.2")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value())
            .expectHeader().exists("Retry-After")
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectBody().jsonPath("$.error").isEqualTo("too many requests")
    }

    @Test
    fun `should isolate counts per IP for per-ip enabled routes`() {
        val limit = 50
        // Fill up for IP 1.1.1.1
        repeat(limit) {
            webTestClient.get().uri("/api/bar/ip-test")
                .header("X-Forwarded-For", "1.1.1.1")
                .exchange()
                .expectStatus().isOk
        }
        
        // IP 1.1.1.1 should be blocked
        webTestClient.get().uri("/api/bar/ip-test")
            .header("X-Forwarded-For", "1.1.1.1")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value())
            
        // IP 3.3.3.3 should be allowed
        webTestClient.get().uri("/api/bar/ip-test")
            .header("X-Forwarded-For", "3.3.3.3")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `should use default limit for routes without specific limit`() {
        webTestClient.get().uri("/api/any-other")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals(RateLimitConstants.HEADER_LIMIT, "200")
    }
}

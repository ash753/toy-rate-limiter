package com.ratelimiter.proxy

import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.junit5.WireMockTest
import com.ratelimiter.common.RateLimitConstants
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "proxy.routes[0].path-pattern=/api/**",
        "proxy.routes[0].target-uri=http://localhost:8090",
        "proxy.routes[0].limit=100"
    ]
)
@Testcontainers
@WireMockTest(httpPort = 8090)
class RateLimitFailOpenIntegrationTest {

    lateinit var webTestClient: WebTestClient

    @Autowired
    lateinit var context: ApplicationContext

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
        webTestClient = WebTestClient.bindToApplicationContext(context).build()
        
        stubFor(get(urlMatching("/api/.*"))
            .willReturn(aResponse()
                .withStatus(HttpStatus.OK.value())
                .withHeader("Content-Type", "application/json")
                .withBody("""{"message":"success"}""")))
    }

    @Test
    fun `should allow request even when Redis is down (Fail-Open)`() {
        // given: Redis 정지
        redis.stop()

        // when & then: 요청이 200으로 성공해야 함 (Fail-Open)
        webTestClient.get().uri("/api/test-fail-open")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals(RateLimitConstants.HEADER_LIMIT, "100")
            .expectHeader().valueEquals(RateLimitConstants.HEADER_REMAINING, "100")
            .expectBody().jsonPath("$.message").isEqualTo("success")
    }
}

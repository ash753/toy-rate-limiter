package com.ratelimiter.proxy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorEndpointTest {

    private lateinit var client: WebTestClient

    @Autowired
    private lateinit var context: ApplicationContext

    @BeforeEach
    fun setUp() {
        client = WebTestClient.bindToApplicationContext(context).build()
    }

    @Test
    fun `prometheus endpoint exposes metrics after requests`() {
        // Send a request to trigger metrics
        client.get().uri("/api/test-trigger")
            .exchange()

        client.get().uri("/actuator/prometheus")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .value { body ->
                assertThat(body).contains("ratelimit_requests_total")
                assertThat(body).contains("ratelimit_redis_latency_seconds")
                assertThat(body).contains("resilience4j_circuitbreaker_state")
            }
    }
}

package com.ratelimiter.limiter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicInteger

@Testcontainers
@SpringBootTest
class RedisSlidingWindowRateLimiterTest {

    companion object {
        @Container
        val redis = GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)

        @JvmStatic
        @DynamicPropertySource
        fun redisProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.firstMappedPort }
        }
    }

    @Autowired
    lateinit var rateLimiter: RateLimiter

    @Test
    fun `should allow request when under limit`() {
        val key = "rl:test:under"
        val limit = 5

        rateLimiter.check(key, limit)
            .`as`(StepVerifier::create)
            .assertNext { result ->
                assertThat(result.allowed).isTrue()
                assertThat(result.remainCount).isEqualTo(4)
            }
            .verifyComplete()
    }

    @Test
    fun `should block request when over limit`() {
        val key = "rl:test:over"
        val limit = 3

        // 3번 요청 허용
        Flux.range(1, 3)
            .flatMap { rateLimiter.check(key, limit) }
            .`as`(StepVerifier::create)
            .expectNextCount(3)
            .verifyComplete()

        // 4번째 요청 차단
        rateLimiter.check(key, limit)
            .`as`(StepVerifier::create)
            .assertNext { result ->
                assertThat(result.allowed).isFalse()
                assertThat(result.retryAfterSeconds).isGreaterThan(0)
            }
            .verifyComplete()
    }

    @Test
    fun `concurrent requests should be counted atomically`() {
        val key = "rl:test:concurrent"
        val limit = 20
        val totalRequests = 40
        val allowedCount = AtomicInteger(0)

        // 100개의 요청을 동시에 발송
        Flux.range(1, totalRequests)
            .flatMap { rateLimiter.check(key, limit) }
            .doOnNext { result ->
                if (result.allowed) {
                    allowedCount.incrementAndGet()
                }
            }
            .`as`(StepVerifier::create)
            .expectNextCount(totalRequests.toLong())
            .verifyComplete()

        // 아무리 많은 요청이 와도 정확히 limit(50)개만 허용되어야 함
        assertThat(allowedCount.get()).isEqualTo(limit)
    }
}

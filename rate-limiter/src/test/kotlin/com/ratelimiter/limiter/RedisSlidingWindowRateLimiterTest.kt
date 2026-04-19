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
import java.util.concurrent.CountDownLatch
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

        val result = rateLimiter.check(key, limit).block()!!

        assertThat(result.allowed).isTrue()
        assertThat(result.remainCount).isEqualTo(4)
    }

    @Test
    fun `should block request when over limit`() {
        val key = "rl:test:over"
        val limit = 3

        // 3번 요청 허용
        repeat(3) {
            val r = rateLimiter.check(key, limit).block()!!
            assertThat(r.allowed).isTrue()
        }

        // 4번째 요청 차단
        val blockResult = rateLimiter.check(key, limit).block()!!
        assertThat(blockResult.allowed).isFalse()
        assertThat(blockResult.retryAfterSeconds).isGreaterThan(0)
    }

    @Test
    fun `concurrent requests should be counted atomically`() {
        val key = "rl:test:concurrent"
        val limit = 50
        val totalRequests = 100
        val latch = CountDownLatch(totalRequests)
        val allowedCount = AtomicInteger(0)

        // 100개의 요청을 동시에 발송
        repeat(totalRequests) {
            Thread {
                try {
                    val result = rateLimiter.check(key, limit).block()
                    if (result?.allowed == true) {
                        allowedCount.incrementAndGet()
                    }
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        latch.await()

        // 아무리 많은 요청이 와도 정확히 limit(50)개만 허용되어야 함
        assertThat(allowedCount.get()).isEqualTo(50)
    }
}

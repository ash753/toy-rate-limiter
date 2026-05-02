package com.ratelimiter.limiter

import com.ratelimiter.config.ProxyRoutesProperties
import com.ratelimiter.metrics.RateLimitMetrics
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Flux

class RedisSlidingWindowRateLimiterMetricsTest {

    private lateinit var registry: MeterRegistry
    private lateinit var redisTemplate: ReactiveStringRedisTemplate
    private lateinit var slidingWindowScript: RedisScript<List<Long>>
    private lateinit var proxyRoutesProperties: ProxyRoutesProperties
    private lateinit var metrics: RateLimitMetrics
    private lateinit var rateLimiter: RedisSlidingWindowRateLimiter

    @BeforeEach
    fun setUp() {
        registry = SimpleMeterRegistry()
        redisTemplate = mockk()
        slidingWindowScript = mockk()
        proxyRoutesProperties = ProxyRoutesProperties(windowSize = 10, ttl = 10, routes = emptyList())
        metrics = RateLimitMetrics(registry)
        
        rateLimiter = RedisSlidingWindowRateLimiter(
            redisTemplate,
            slidingWindowScript,
            proxyRoutesProperties,
            metrics,
            CircuitBreakerRegistry.ofDefaults()
        )
    }

    @Test
    fun `should record redis latency on success`() {
        // given
        every {
            redisTemplate.execute(any<RedisScript<List<Long>>>(), any(), any())
        } returns Flux.just(listOf(1L, 9L, 0L))

        // when
        rateLimiter.check("key", 10).block()

        // then
        val timer = registry.find("ratelimit.redis.latency").timer()
        assertThat(timer?.count()).isEqualTo(1L)
    }

    @Test
    fun `should record failopen and record latency on error`() {
        // given
        every {
            redisTemplate.execute(any<RedisScript<List<Long>>>(), any(), any())
        } returns Flux.error(RuntimeException("Redis error"))

        // when
        rateLimiter.check("key", 10).block()

        // then
        val timer = registry.find("ratelimit.redis.latency").timer()
        assertThat(timer?.count()).isEqualTo(1L) // doFinally records latency regardless of success/error

        val failCounter = registry.find("ratelimit.failopen")
            .tag("reason", "RuntimeException")
            .counter()
        assertThat(failCounter?.count()).isEqualTo(1.0)
    }
}

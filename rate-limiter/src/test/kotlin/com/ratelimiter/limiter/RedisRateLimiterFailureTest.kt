package com.ratelimiter.limiter

import com.ratelimiter.common.RateLimitConstants.REDIS_RATE_LIMITER_NAME
import com.ratelimiter.config.ProxyRoutesProperties
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import java.time.Duration

class RedisRateLimiterFailureTest {

    private lateinit var redisTemplate: ReactiveStringRedisTemplate
    private lateinit var slidingWindowScript: RedisScript<List<Long>>
    private lateinit var proxyRoutesProperties: ProxyRoutesProperties
    private lateinit var circuitBreakerRegistry: CircuitBreakerRegistry
    private lateinit var rateLimiter: RedisSlidingWindowRateLimiter

    @BeforeEach
    fun setUp() {
        redisTemplate = mockk()
        slidingWindowScript = mockk()
        proxyRoutesProperties = ProxyRoutesProperties(
            windowSize = 10,
            ttl = 10,
            routes = emptyList()
        )
        // 테스트를 위해 서킷 브레이커 설정을 아주 작게 조정 (5번 호출 중 1번만 실패해도 오픈)
        val config = io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
            .failureRateThreshold(20f)
            .minimumNumberOfCalls(5)
            .slidingWindowSize(10)
            .build()
        
        circuitBreakerRegistry = CircuitBreakerRegistry.of(config)
        
        rateLimiter = RedisSlidingWindowRateLimiter(
            redisTemplate,
            slidingWindowScript,
            proxyRoutesProperties,
            circuitBreakerRegistry
        )
    }

    @Test
    fun `should fail-open when Redis connection fails`() {
        // given
        val key = "rl:fail:conn"
        val limit = 10
        every {
            redisTemplate.execute(any<RedisScript<List<Long>>>(), any(), any())
        } returns Flux.error(RedisConnectionFailureException("Connection refused"))

        // when & then
        rateLimiter.check(key, limit)
            .`as`(StepVerifier::create)
            .assertNext { result ->
                assertThat(result.allowed).isTrue() // Fail-open
                assertThat(result.remainCount).isEqualTo(limit)
            }
            .verifyComplete()
    }

    @Test
    fun `should fail-open when Redis timeout occurs`() {
        // given
        val key = "rl:fail:timeout"
        val limit = 10
        
        // 500ms 보다 긴 지연 발생
        every {
            redisTemplate.execute(any<RedisScript<List<Long>>>(), any(), any())
        } returns Flux.just(listOf(1L, 9L, 0L)).delayElements(Duration.ofSeconds(1))

        // when & then
        rateLimiter.check(key, limit)
            .`as`(StepVerifier::create)
            .assertNext { result ->
                assertThat(result.allowed).isTrue() // Fail-open by timeout
            }
            .verifyComplete()
    }

    @Test
    fun `should transition to OPEN state after continuous failures`() {
        // given
        val key = "rl:fail:cb"
        val limit = 10
        
        val cb = circuitBreakerRegistry.circuitBreaker(REDIS_RATE_LIMITER_NAME)
        
        every {
            redisTemplate.execute(any<RedisScript<List<Long>>>(), any(), any())
        } returns Flux.error(RedisConnectionFailureException("Fail"))

        // when: 실패 발생 (최소 호출 횟수 5회 이상 수행)
        repeat(6) {
            rateLimiter.check(key, limit).block()
        }

        // then: 서킷 오픈 확인
        assertThat(cb.state).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN)
        
        // OPEN 상태에서도 fail-open 로직에 의해 요청은 허용되어야 함
        rateLimiter.check(key, limit)
            .`as`(StepVerifier::create)
            .assertNext { result ->
                assertThat(result.allowed).isTrue()
            }
            .verifyComplete()
    }
}

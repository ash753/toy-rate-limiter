package com.ratelimiter.limiter

import com.ratelimiter.common.RateLimitConstants.REDIS_RATE_LIMITER_NAME
import com.ratelimiter.config.ProxyRoutesProperties
import com.ratelimiter.metrics.RateLimitMetrics
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
    private lateinit var metrics: RateLimitMetrics
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
        // 테스트를 위해 서킷 브레이커 설정을 아주 작게 조정
        val config = io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
            .failureRateThreshold(20f)
            .minimumNumberOfCalls(5)
            .slidingWindowSize(10)
            .waitDurationInOpenState(Duration.ofMillis(500)) // 복구 테스트를 위해 대기 시간 단축
            .permittedNumberOfCallsInHalfOpenState(2)
            .build()
        
        circuitBreakerRegistry = CircuitBreakerRegistry.of(config)
        metrics = RateLimitMetrics(SimpleMeterRegistry())
        
        rateLimiter = RedisSlidingWindowRateLimiter(
            redisTemplate,
            slidingWindowScript,
            proxyRoutesProperties,
            metrics,
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

    @Test
    fun `should recover to CLOSED state after success in HALF_OPEN`() {
        // given
        val key = "rl:recover"
        val limit = 10
        val cb = circuitBreakerRegistry.circuitBreaker(REDIS_RATE_LIMITER_NAME)
        
        // 1. 서킷 오픈시키기
        every {
            redisTemplate.execute(any<RedisScript<List<Long>>>(), any(), any())
        } returns Flux.error(RedisConnectionFailureException("Fail"))
        
        repeat(6) { rateLimiter.check(key, limit).block() }
        assertThat(cb.state).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN)
        
        // 2. 대기 시간(500ms) 지날 때까지 대기
        Thread.sleep(600)
        
        // 3. Redis 복구 상황 시뮬레이션
        every {
            redisTemplate.execute(any<RedisScript<List<Long>>>(), any(), any())
        } returns Flux.just(listOf(1L, 9L, 0L)) // 성공 응답
        
        // 4. HALF_OPEN 상태 확인을 위한 호출
        // Resilience4j는 OPEN 상태에서 대기 시간이 지난 후 첫 호출이 들어와야 HALF_OPEN으로 전환됨
        rateLimiter.check(key, limit).block()
        assertThat(cb.state).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.HALF_OPEN)
        
        // 5. 추가 성공 호출로 CLOSED 전환 유도
        rateLimiter.check(key, limit).block()
        
        // then: 복구 확인
        assertThat(cb.state).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED)
    }
}

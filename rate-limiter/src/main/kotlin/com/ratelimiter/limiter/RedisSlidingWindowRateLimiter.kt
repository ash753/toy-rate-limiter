package com.ratelimiter.limiter

import com.ratelimiter.common.RateLimitConstants.REDIS_RATE_LIMITER_NAME
import com.ratelimiter.config.ProxyRoutesProperties
import com.ratelimiter.metrics.RateLimitMetrics
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.time.Duration

@Component
class RedisSlidingWindowRateLimiter(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val slidingWindowScript: RedisScript<List<Long>>,
    private val proxyRoutesProperties: ProxyRoutesProperties,
    private val metrics: RateLimitMetrics,
    circuitBreakerRegistry: CircuitBreakerRegistry,
) : RateLimiter {

    private val log = LoggerFactory.getLogger(javaClass)
    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker(REDIS_RATE_LIMITER_NAME)

    override fun check(keyPrefix: String, limit: Int): Mono<RateLimitResult> {
        return Mono.defer {
            val sample = Timer.start()
            
            // ARGV: [limit, window_size, ttl]
            val args = listOf(
                limit.toString(),
                proxyRoutesProperties.windowSize.toString(),
                proxyRoutesProperties.ttl.toString()
            )

            redisTemplate.execute(
                slidingWindowScript,
                listOf(keyPrefix),
                args
            )
                .next()
                .map { result ->
                    RateLimitResult(
                        allowed = result[0] == 1L,
                        remainCount = result[1].toInt().coerceAtLeast(0),
                        retryAfterSeconds = result[2].toInt(),
                        isFailOpen = false
                    )
                }
                .doFinally { sample.stop(metrics.redisLatency) }
        }
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
            .timeout(Duration.ofMillis(500))
            .onErrorResume { t ->
                log.warn("[RateLimit] fail-open due to Redis error. key={}, errorType={}, message={}",
                    keyPrefix, t.javaClass.simpleName, t.message)
                metrics.recordFailopen(t.javaClass.simpleName)
                Mono.just(RateLimitResult(allowed = true, remainCount = limit, retryAfterSeconds = 0, isFailOpen = true))
            }
    }
}

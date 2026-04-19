package com.ratelimiter.limiter

import com.ratelimiter.config.ProxyRoutesProperties
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class RedisSlidingWindowRateLimiter(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val slidingWindowScript: RedisScript<List<Long>>,
    private val proxyRoutesProperties: ProxyRoutesProperties
) : RateLimiter {

    override fun check(keyPrefix: String, limit: Int): Mono<RateLimitResult> {
        // ARGV: [limit, window_size, ttl]
        val args = listOf(
            limit.toString(),
            proxyRoutesProperties.windowSize.toString(),
            proxyRoutesProperties.ttl.toString()
        )

        return redisTemplate.execute(
            slidingWindowScript,
            listOf(keyPrefix),
            args
        )
            .next()
            .map { result ->
                RateLimitResult(
                    allowed = result[0] == 1L,
                    remainCount = result[1].toInt().coerceAtLeast(0),
                    retryAfterSeconds = result[2].toInt()
                )
            }
    }
}

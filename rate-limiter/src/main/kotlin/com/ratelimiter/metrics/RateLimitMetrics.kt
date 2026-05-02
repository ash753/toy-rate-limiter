package com.ratelimiter.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

@Component
class RateLimitMetrics(private val registry: MeterRegistry) {

    fun recordRequest(endpoint: String, result: String) {
        Counter.builder("ratelimit.requests")
            .tag("endpoint", endpoint)
            .tag("result", result)
            .register(registry)
            .increment()
    }

    fun recordFailopen(reason: String) {
        Counter.builder("ratelimit.failopen")
            .tag("reason", reason)
            .register(registry)
            .increment()
    }

    val redisLatency: Timer = Timer.builder("ratelimit.redis.latency")
        .description("Latency of Redis sliding window script execution")
        .publishPercentileHistogram()
        .register(registry)
}

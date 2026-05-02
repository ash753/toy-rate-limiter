package com.ratelimiter.config

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MetricsConfig {

    @Bean
    fun registerResilience4jMetrics(
        circuitBreakerRegistry: CircuitBreakerRegistry,
        meterRegistry: MeterRegistry
    ): TaggedCircuitBreakerMetrics {
        val metrics = TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry)
        metrics.bindTo(meterRegistry)
        return metrics
    }
}

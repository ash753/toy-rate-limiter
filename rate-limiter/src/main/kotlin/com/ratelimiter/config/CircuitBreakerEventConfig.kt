package com.ratelimiter.config

import com.ratelimiter.common.RateLimitConstants.REDIS_RATE_LIMITER_NAME
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration

@Configuration
class CircuitBreakerEventConfig(registry: CircuitBreakerRegistry) {

    private val log = LoggerFactory.getLogger(javaClass)

    init {
        registry.circuitBreaker(REDIS_RATE_LIMITER_NAME).eventPublisher
            .onStateTransition { event ->
                val transition = event.stateTransition
                when (transition.toState) {
                    io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN -> 
                        log.warn("[CB] State transition: {} -> {}", transition.fromState, transition.toState)
                    else -> 
                        log.info("[CB] State transition: {} -> {}", transition.fromState, transition.toState)
                }
            }
    }
}

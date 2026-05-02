package com.ratelimiter.proxy

import com.ratelimiter.config.ProxyRoutesProperties
import com.ratelimiter.limiter.RateLimitResult
import com.ratelimiter.limiter.RateLimiter
import com.ratelimiter.metrics.RateLimitMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

class MetricsUnitTest {

    private lateinit var registry: MeterRegistry
    private lateinit var metrics: RateLimitMetrics
    private lateinit var routeMatcher: RouteMatcher
    private lateinit var rateLimiter: RateLimiter
    private lateinit var proxyRoutesProperties: ProxyRoutesProperties
    private lateinit var filter: RateLimitWebFilter

    @BeforeEach
    fun setUp() {
        registry = SimpleMeterRegistry()
        metrics = RateLimitMetrics(registry)
        routeMatcher = mockk()
        rateLimiter = mockk()
        proxyRoutesProperties = ProxyRoutesProperties(defaultLimit = 100, routes = emptyList())
        filter = RateLimitWebFilter(routeMatcher, rateLimiter, proxyRoutesProperties, metrics)
    }

    @Test
    fun `should record allowed request metric`() {
        // given
        val route = ProxyRoutesProperties.Route(pathPattern = "/api/**", targetUri = "http://target")
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test").build())
        val chain = mockk<WebFilterChain>()
        
        every { routeMatcher.findMatch(any()) } returns route
        every { rateLimiter.check(any(), any()) } returns Mono.just(RateLimitResult(allowed = true, remainCount = 99, retryAfterSeconds = 0))
        every { chain.filter(any()) } returns Mono.empty()

        // when
        filter.filter(exchange, chain).block()

        // then
        val counter = registry.find("ratelimit.requests")
            .tag("endpoint", "/api/**")
            .tag("result", "allowed")
            .counter()
        
        assertThat(counter?.count()).isEqualTo(1.0)
    }

    @Test
    fun `should record blocked request metric`() {
        // given
        val route = ProxyRoutesProperties.Route(pathPattern = "/api/**", targetUri = "http://target")
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test").build())
        val chain = mockk<WebFilterChain>()
        
        every { routeMatcher.findMatch(any()) } returns route
        every { rateLimiter.check(any(), any()) } returns Mono.just(RateLimitResult(allowed = false, remainCount = 0, retryAfterSeconds = 60))

        // when
        filter.filter(exchange, chain).block()

        // then
        val counter = registry.find("ratelimit.requests")
            .tag("endpoint", "/api/**")
            .tag("result", "blocked")
            .counter()
        
        assertThat(counter?.count()).isEqualTo(1.0)
    }

    @Test
    fun `should record failopen request metric`() {
        // given
        val route = ProxyRoutesProperties.Route(pathPattern = "/api/**", targetUri = "http://target")
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test").build())
        val chain = mockk<WebFilterChain>()
        
        every { routeMatcher.findMatch(any()) } returns route
        every { rateLimiter.check(any(), any()) } returns Mono.just(RateLimitResult(allowed = true, remainCount = 100, retryAfterSeconds = 0, isFailOpen = true))
        every { chain.filter(any()) } returns Mono.empty()

        // when
        filter.filter(exchange, chain).block()

        // then
        val counter = registry.find("ratelimit.requests")
            .tag("endpoint", "/api/**")
            .tag("result", "failopen")
            .counter()
        
        assertThat(counter?.count()).isEqualTo(1.0)
    }
}

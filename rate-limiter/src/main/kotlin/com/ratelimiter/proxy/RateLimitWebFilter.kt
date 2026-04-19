package com.ratelimiter.proxy

import com.ratelimiter.common.IpExtractor
import com.ratelimiter.common.RateLimitConstants
import com.ratelimiter.config.ProxyRoutesProperties
import com.ratelimiter.limiter.RateLimiter
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@Component
@Order(1)
class RateLimitWebFilter(
    private val routeMatcher: RouteMatcher,
    private val rateLimiter: RateLimiter,
    private val proxyRoutesProperties: ProxyRoutesProperties,
) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.pathWithinApplication()
        val route = routeMatcher.findMatch(path) ?: return chain.filter(exchange)
        
        val ip = IpExtractor.extract(exchange.request)
        val keyPrefix = if (route.perIp) "rl:${route.pathPattern}:$ip" else "rl:${route.pathPattern}"
        val limit = route.limit ?: proxyRoutesProperties.defaultLimit

        return rateLimiter.check(keyPrefix, limit)
            .flatMap { result ->
                val response = exchange.response
                response.headers.set(RateLimitConstants.HEADER_LIMIT, limit.toString())
                response.headers.set(RateLimitConstants.HEADER_REMAINING, result.remainCount.toString())

                if (result.allowed) {
                    chain.filter(exchange)
                } else {
                    response.statusCode = HttpStatus.TOO_MANY_REQUESTS
                    response.headers.set(HttpHeaders.RETRY_AFTER, result.retryAfterSeconds.toString())
                    response.headers.contentType = MediaType.APPLICATION_JSON
                    
                    val body = RateLimitConstants.TOO_MANY_REQUESTS_BODY.toByteArray()
                    val buffer = response.bufferFactory().wrap(body)
                    response.writeWith(Mono.just(buffer))
                }
            }
    }
}

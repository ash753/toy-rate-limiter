package com.ratelimiter.proxy

import com.ratelimiter.config.ProxyRoutesProperties
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import org.springframework.web.util.pattern.PathPatternParser
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import java.net.URI

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ProxyWebFilter(
    private val proxyRoutesProperties: ProxyRoutesProperties,
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper
) : WebFilter {

    private val pathPatternParser = PathPatternParser()

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val request = exchange.request

        val matchedRoute = proxyRoutesProperties.routes.find { route ->
            val pattern = pathPatternParser.parse(route.pathPattern)
            pattern.matches(request.path.pathWithinApplication())
        }

        if (matchedRoute == null) {
            return chain.filter(exchange)
        }

        val targetUri = URI.create(matchedRoute.targetUri)
        val forwardedUri = URI(
            targetUri.scheme,
            targetUri.authority,
            request.uri.path,
            request.uri.query,
            null
        )

        return webClient.method(request.method)
            .uri(forwardedUri)
            .headers { headers ->
                headers.addAll(request.headers)
                headers.remove("Host") // Target host will be set by WebClient
            }
            .body(request.body, org.springframework.core.io.buffer.DataBuffer::class.java)
            .exchangeToMono { response ->
                exchange.response.statusCode = response.statusCode()
                exchange.response.headers.addAll(response.headers().asHttpHeaders())
                exchange.response.writeWith(response.bodyToFlux(org.springframework.core.io.buffer.DataBuffer::class.java))
            }
    }
}

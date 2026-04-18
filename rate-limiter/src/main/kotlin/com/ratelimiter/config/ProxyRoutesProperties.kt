package com.ratelimiter.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "proxy")
data class ProxyRoutesProperties(val routes: List<Route> = emptyList()) {
    data class Route(val pathPattern: String, val targetUri: String)
}

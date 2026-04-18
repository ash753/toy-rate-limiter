package com.ratelimiter.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

@ConfigurationProperties(prefix = "proxy")
data class ProxyRoutesProperties(val routes: List<Route> = emptyList()) {
    data class Route(val pathPattern: String, val targetUri: String) {
        val parsedPattern: PathPattern by lazy {
            PathPatternParser.defaultInstance.parse(pathPattern)
        }
    }
}

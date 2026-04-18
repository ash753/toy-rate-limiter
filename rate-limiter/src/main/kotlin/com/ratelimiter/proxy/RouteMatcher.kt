package com.ratelimiter.proxy

import com.ratelimiter.config.ProxyRoutesProperties
import org.springframework.http.server.PathContainer
import org.springframework.stereotype.Component
import org.springframework.web.util.pattern.PathPattern

@Component
class RouteMatcher(proxyRoutesProperties: ProxyRoutesProperties) {

    private val sortedRoutes = proxyRoutesProperties.routes
        .sortedWith(compareBy(PathPattern.SPECIFICITY_COMPARATOR) { it.parsedPattern })

    fun findMatch(path: PathContainer): ProxyRoutesProperties.Route? {
        return sortedRoutes.find { it.parsedPattern.matches(path) }
    }
}

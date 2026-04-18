package com.ratelimiter.proxy

import com.ratelimiter.config.ProxyRoutesProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.server.PathContainer

class RouteMatcherTest {

    @Test
    fun `should match most specific route first`() {
        // given: 순서와 상관없이 섞어서 제공
        val routes = listOf(
            ProxyRoutesProperties.Route("/api/**", "http://api-generic"),
            ProxyRoutesProperties.Route("/api/foo", "http://api-specific"),
            ProxyRoutesProperties.Route("/**", "http://root")
        )
        val properties = ProxyRoutesProperties(routes)
        val routeMatcher = RouteMatcher(properties)

        // when & then
        
        // 1. /api/foo 는 /api/** 보다 구체적인 api-specific 에 매칭되어야 함
        val match1 = routeMatcher.findMatch(PathContainer.parsePath("/api/foo"))
        assertEquals("http://api-specific", match1?.targetUri)

        // 2. /api/bar 는 /api/foo 에는 안 맞지만 /api/** 에는 맞음
        val match2 = routeMatcher.findMatch(PathContainer.parsePath("/api/bar"))
        assertEquals("http://api-generic", match2?.targetUri)

        // 3. /other 는 최하위 우선순위인 /** 에 맞음
        val match3 = routeMatcher.findMatch(PathContainer.parsePath("/other"))
        assertEquals("http://root", match3?.targetUri)
    }
}

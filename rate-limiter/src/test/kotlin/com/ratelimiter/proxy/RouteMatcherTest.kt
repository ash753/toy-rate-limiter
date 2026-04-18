package com.ratelimiter.proxy

import com.ratelimiter.config.ProxyRoutesProperties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.server.PathContainer

class RouteMatcherTest {

    @Test
    fun `should match most specific route first with rate limit info`() {
        // given
        val routes = listOf(
            ProxyRoutesProperties.Route("/api/**", "http://api-generic", limit = 200),
            ProxyRoutesProperties.Route("/api/foo", "http://api-specific", limit = 100, perIp = true),
            ProxyRoutesProperties.Route("/**", "http://root")
        )
        val properties = ProxyRoutesProperties(defaultLimit = 500, routes = routes)
        val routeMatcher = RouteMatcher(properties)

        // when & then
        
        // 1. /api/foo 는 전용 제한(100)과 perIp(true) 정보를 포함해야 함
        val match1 = routeMatcher.findMatch(PathContainer.parsePath("/api/foo"))
        assertNotNull(match1)
        assertEquals("http://api-specific", match1?.targetUri)
        assertEquals(100, match1?.limit)
        assertTrue(match1?.perIp == true)

        // 2. /api/bar 는 /api/** 에 매칭되며 limit(200)을 가짐
        val match2 = routeMatcher.findMatch(PathContainer.parsePath("/api/bar"))
        assertNotNull(match2)
        assertEquals("http://api-generic", match2?.targetUri)
        assertEquals(200, match2?.limit)
        assertFalse(match2?.perIp == true)

        // 3. /other 는 /** 에 매칭되며 limit 은 null 이어서 기본값 상속 대상임
        val match3 = routeMatcher.findMatch(PathContainer.parsePath("/other"))
        assertNotNull(match3)
        assertEquals("http://root", match3?.targetUri)
        assertNull(match3?.limit)
    }

    @Test
    fun `should return null when no route matches`() {
        // given
        val routes = listOf(
            ProxyRoutesProperties.Route("/api/**", "http://api-generic")
        )
        val properties = ProxyRoutesProperties(routes = routes)
        val routeMatcher = RouteMatcher(properties)

        // when
        val match = routeMatcher.findMatch(PathContainer.parsePath("/health"))

        // then
        assertNull(match)
    }
}

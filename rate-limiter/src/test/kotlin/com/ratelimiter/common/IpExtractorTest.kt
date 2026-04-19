package com.ratelimiter.common

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpRequest
import java.net.InetSocketAddress

class IpExtractorTest {

    @Test
    fun `should extract first IP from X-Forwarded-For header`() {
        // given
        val request = mockk<ServerHttpRequest>()
        val headers = HttpHeaders()
        headers.add("X-Forwarded-For", "1.1.1.1, 2.2.2.2")
        headers.add("X-Real-IP", "3.3.3.3")
        every { request.headers } returns headers

        // when
        val ip = IpExtractor.extract(request)

        // then
        assertEquals("1.1.1.1", ip)
    }

    @Test
    fun `should extract from X-Real-IP if X-Forwarded-For is missing`() {
        // given
        val request = mockk<ServerHttpRequest>()
        val headers = HttpHeaders()
        headers.add("X-Real-IP", "3.3.3.3")
        every { request.headers } returns headers

        // when
        val ip = IpExtractor.extract(request)

        // then
        assertEquals("3.3.3.3", ip)
    }

    @Test
    fun `should extract from remoteAddress if both headers are missing`() {
        // given
        val request = mockk<ServerHttpRequest>()
        val headers = HttpHeaders()
        every { request.headers } returns headers
        every { request.remoteAddress } returns InetSocketAddress("4.4.4.4", 8080)

        // when
        val ip = IpExtractor.extract(request)

        // then
        assertEquals("4.4.4.4", ip)
    }

    @Test
    fun `should return unknown if all sources are missing`() {
        // given
        val request = mockk<ServerHttpRequest>()
        every { request.headers } returns HttpHeaders()
        every { request.remoteAddress } returns null

        // when
        val ip = IpExtractor.extract(request)

        // then
        assertEquals("unknown", ip)
    }
}

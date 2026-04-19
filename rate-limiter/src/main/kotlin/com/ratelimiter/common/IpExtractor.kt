package com.ratelimiter.common

import org.springframework.http.server.reactive.ServerHttpRequest

object IpExtractor {
    fun extract(request: ServerHttpRequest): String {
        // 1. X-Forwarded-For 헤더에서 첫 번째 IP 추출 시도
        request.headers.getFirst("X-Forwarded-For")
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        // 2. X-Real-IP 헤더 확인
        request.headers.getFirst("X-Real-IP")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        // 3. 직접 연결된 Remote Address 확인 및 Fallback
        return request.remoteAddress?.address?.hostAddress ?: "unknown"
    }
}

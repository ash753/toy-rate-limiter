package com.ratelimiter.limiter

import reactor.core.publisher.Mono

interface RateLimiter {
    /**
     * 특정 키에 대한 처리율 제한을 확인하고 카운트를 증가시킵니다.
     * @param keyPrefix Redis 키 접두사
     * @param limit 허용 요청 수
     * @return 처리율 제한 결과
     */
    fun check(keyPrefix: String, limit: Int): Mono<RateLimitResult>
}

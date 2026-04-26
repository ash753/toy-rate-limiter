package com.ratelimiter.common

object RateLimitConstants {
    const val REDIS_RATE_LIMITER_NAME = "redisRateLimiter"
    const val HEADER_LIMIT = "X-RateLimit-Limit"
    const val HEADER_REMAINING = "X-RateLimit-Remaining"
    const val TOO_MANY_REQUESTS_BODY = """{"error":"too many requests"}"""
}

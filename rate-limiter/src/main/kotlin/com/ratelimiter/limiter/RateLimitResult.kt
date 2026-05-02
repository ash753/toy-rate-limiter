package com.ratelimiter.limiter

data class RateLimitResult(
    val allowed: Boolean,
    val remainCount: Int,
    val retryAfterSeconds: Int,
    val isFailOpen: Boolean = false
)

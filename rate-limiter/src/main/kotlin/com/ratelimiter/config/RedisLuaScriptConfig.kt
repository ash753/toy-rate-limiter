package com.ratelimiter.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.script.RedisScript

@Configuration
class RedisLuaScriptConfig {

    @Bean
    fun slidingWindowScript(): RedisScript<List<Long>> {
        return RedisScript.of(
            ClassPathResource("scripts/sliding_window.lua"),
            List::class.java as Class<List<Long>>
        )
    }
}

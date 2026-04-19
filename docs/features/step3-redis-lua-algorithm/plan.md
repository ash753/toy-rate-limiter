# Step 3. Redis 연동 + Lua 스크립트 - 실행 계획

## 작업 순서

### 1. 의존성 추가 (rate-limiter 모듈)

```kotlin
implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
```

### 2. Redis 연결 설정

`application-local.yaml`:
```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 500ms
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
```

### 3. Lua 스크립트 리소스 파일로 분리

`src/main/resources/scripts/sliding_window.lua`에 스크립트 저장.

```kotlin
@Configuration
class LuaScriptConfig {
    @Bean
    fun slidingWindowScript(): RedisScript<List<Long>> {
        return RedisScript.of(
            ClassPathResource("scripts/sliding_window.lua"),
            List::class.java as Class<List<Long>>
        )
    }
}
```

### 4. RateLimiter 구현

```kotlin
@Component
class RedisSlidingWindowRateLimiter(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val slidingWindowScript: RedisScript<List<Long>>,
    private val proxyRoutesProperties: ProxyRoutesProperties
) : RateLimiter {

    override fun check(keyPrefix: String, limit: Int): Mono<RateLimitResult> {
        // ARGV: [limit, window_size, ttl]
        val args = listOf(
            limit.toString(),
            proxyRoutesProperties.windowSize.toString(),
            proxyRoutesProperties.ttl.toString()
        )

        return redisTemplate.execute(slidingWindowScript, listOf(keyPrefix), args)
            .next()
            .map { result ->
                RateLimitResult(
                    allowed = result[0] == 1L,
                    remainCount = result[1].toInt().coerceAtLeast(0),
                    retryAfterSeconds = result[2].toInt()
                )
            }
    }
}
```

### 5. 통합 테스트 환경

`build.gradle.kts`:
```kotlin
testImplementation("org.testcontainers:testcontainers")
testImplementation("org.testcontainers:testcontainers-junit-jupiter")
```

### 6. JVM 설정 (JDK 21+ 대응)

Mockito/ByteBuddy 경고 제거를 위해 `jvmArgs("-XX:+EnableDynamicAgentLoading")` 추가.

## 예상 파일 위치

```
rate-limiter/src/main/
├── kotlin/com/ratelimiter/
│   ├── config/LuaScriptConfig.kt
│   └── limiter/
│       ├── RateLimiter.kt
│       ├── RateLimitResult.kt
│       └── RedisSlidingWindowRateLimiter.kt
└── resources/scripts/sliding_window.lua

rate-limiter/src/test/kotlin/
└── com/ratelimiter/limiter/RedisSlidingWindowRateLimiterTest.kt
```

## 주의사항

### 1. Lua 스크립트의 타입 반환
- Redis EVAL 결과는 Long으로 매핑되므로 `result[0] == 1L` 등으로 비교 필요.

### 2. 설정 값 동적 반영
- `ProxyRoutesProperties`를 통해 윈도우 크기와 TTL을 관리하여 재기동 없이(혹은 설정 주입으로) 변경 가능하게 구성.

## 완료 조건

- 모든 통합 테스트 통과.
- Lua 스크립트가 원자적으로 동작하여 동시성 테스트 통과.
- 설정 파일의 `window-size`, `ttl` 값이 정상적으로 로직에 반영됨.

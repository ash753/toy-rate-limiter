# Step 3. Redis 연동 + Lua 스크립트 - 테스트 계획

## 테스트 목적

- Lua 스크립트가 이동 윈도우 카운터 알고리즘대로 정확히 동작
- 원자성 보장 (동시성 테스트)
- 윈도우 경계 동작
- 실제 Redis와 연동 (Testcontainers)

## 테스트 유형

### 1. Lua 스크립트 단독 테스트 (redis-cli, 수동)

Redis 컨테이너에서 직접 실행해 초기 동작 확인:
```bash
redis-cli --eval sliding_window.lua rl:test , 5 60 120
# → [1, 4, 0]
# 5번 반복 후 6번째 호출 → [0, 0, <retry>]
```

### 2. Testcontainers 통합 테스트 (핵심)

```kotlin
@Testcontainers
@SpringBootTest
class RedisSlidingWindowRateLimiterTest {
    // ... Redis 컨테이너 설정 및 DynamicPropertySource 주입
    @Autowired lateinit var rateLimiter: RateLimiter
}
```

### 테스트 케이스

| # | 케이스 | 시나리오 | 기대 결과 |
|---|---|---|---|
| 1 | 최초 호출 허용 | 신규 key, limit=10, 1회 호출 | `allowed=true, remainCount=9` |
| 2 | limit 초과 차단 | limit=5, 6회 연속 호출 | 1~5회차 허용, 6회차 `allowed=false` |
| 3 | remainCount 감소 | limit=3, 3회 연속 호출 | remainCount: 2, 1, 0 |
| 4 | retry-after 반환 | 차단 응답 | `retryAfterSeconds > 0` |
| 5 | 다른 key 독립 카운팅 | key A에 limit 채움, key B는 허용 | A=차단, B=허용 |

### 3. 동시성 테스트

```kotlin
@Test
fun `concurrent requests are counted atomically`() {
    val limit = 50
    val totalRequests = 100
    // ... CountDownLatch 및 스레드 실행 로직
    assertThat(allowedCount.get()).isEqualTo(50)
}
```

- 100건 동시 요청 → 정확히 limit(50)건만 허용되어야 원자성 입증.

## 주요 Assertion

```kotlin
assertThat(result.allowed).isTrue()
assertThat(result.remainCount).isBetween(0, limit)
assertThat(result.retryAfterSeconds).isGreaterThanOrEqualTo(0)
```

## 완료 조건

- `RedisSlidingWindowRateLimiterTest`의 모든 케이스 통과.
- `ProxyRoutesPropertiesTest`에서 `windowSize`, `ttl` 바인딩 확인.
- 로컬 `application-local.yaml` 설정을 통한 동작 확인.

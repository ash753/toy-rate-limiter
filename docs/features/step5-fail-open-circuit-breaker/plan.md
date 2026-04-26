# Step 5. 장애 처리 (Fail-Open + Circuit Breaker) - 실행 계획

## 작업 순서

### 1. Resilience4j 의존성 추가

```kotlin
implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
implementation("io.github.resilience4j:resilience4j-reactor:2.2.0")
implementation("org.springframework.boot:spring-boot-starter-aop")
```

### 2. application.yaml 설정

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        registerHealthIndicator: true # Actuator 연동
        event-consumer-buffer-size: 10
    instances:
      redisRateLimiter:
        base-config: default
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        record-exceptions:
          - org.springframework.dao.QueryTimeoutException
          - org.springframework.data.redis.RedisConnectionFailureException
          - org.springframework.data.redis.RedisSystemException
          - io.lettuce.core.RedisCommandTimeoutException
        automatic-transition-from-open-to-half-open-enabled: true
```

### 3. RateLimiter에 Circuit Breaker 적용

Reactive이므로 `CircuitBreakerOperator` 사용을 권장합니다.

**연산자 방식 (권장: WebFlux 친화적)**
```kotlin
override fun check(keyPrefix: String, limit: Int): Mono<RateLimitResult> {
  return redisTemplate.execute(slidingWindowScript, listOf(keyPrefix), args)
    .next()
    .map { toResult(it) }
    .transformDeferred(CircuitBreakerOperator.of(circuitBreaker)) // Circuit Breaker 적용
    .timeout(Duration.ofMillis(500)) // Redis 응답 타임아웃 강제
    .onErrorResume { t ->
      // CallNotPermittedException (CB OPEN 상태) 및 Redis 에러 모두 fail-open 처리
      log.warn("[RateLimit] fail-open due to Redis error. key={}, errorType={}, message={}", 
               keyPrefix, t.javaClass.simpleName, t.message)
      Mono.just(RateLimitResult(allowed = true, remainCount = limit, retryAfterSeconds = 0))
    }
}
```

### 4. RateLimitWebFilter는 변경 거의 없음

`RateLimiter.check()`가 항상 `RateLimitResult`를 반환(fail-open 포함)하므로 WebFilter 로직은 그대로 유지.

단, fail-open 시 `remainCount`를 limit으로 반환하므로 헤더에 그대로 노출됨.

### 5. Circuit Breaker 상태 전이 리스너 (선택)

```kotlin
@Configuration
class CircuitBreakerEventConfig(registry: CircuitBreakerRegistry) {
  init {
    registry.circuitBreaker(REDIS_RATE_LIMITER_NAME).eventPublisher
      .onStateTransition { e ->
        log.warn("[CB] {} -> {}", e.stateTransition.fromState, e.stateTransition.toState)
      }
  }
}
```

## 완료된 파일 경로

```
rate-limiter/src/main/kotlin/com/ratelimiter/
├── limiter/
│   └── RedisSlidingWindowRateLimiter.kt  (수정: CB 연산자 적용)
├── config/
│   └── CircuitBreakerEventConfig.kt  (신규)
└── common/
    └── RateLimitConstants.kt  (수정: REDIS_RATE_LIMITER_NAME 추가)
```

## 주의사항

### 1. Reactive와 어노테이션 방식 혼용 주의
- `@CircuitBreaker`는 Mono/Flux도 지원하지만, AOP 기반이라 디버깅 복잡
- 연산자 방식이 plain하고 테스트 쉬움

### 2. fail-open 시 응답 헤더 값
- 요구사항: `X-RateLimit-Remaining`에 `limit` 값 사용 (보수적이지만 클라이언트 혼란 최소)

### 3. Circuit Breaker가 열려있을 때도 fail-open 경로 공유
- `CircuitBreakerOperator`는 OPEN 상태일 때 `CallNotPermittedException` 발생
- `onErrorResume`에서 이 예외도 동일하게 처리 (fail-open)

### 4. record-exceptions 범위 주의
- 너무 넓으면 예상치 못한 에러까지 fail-open (보안 위험)
- Redis 관련 예외만 정확히 명시

## 완료 조건

- [x] Redis 컨테이너 정지 시 요청이 200으로 통과
- [x] 연속 실패 시 Circuit Breaker OPEN (로그로 확인)
- [x] Redis 복구 후 자동으로 CLOSED 복귀
- [x] Fail-open 로그가 WARN 레벨로 기록
- [x] 기존 Step 4 테스트 모두 여전히 통과 (정상 경로 회귀 없음)

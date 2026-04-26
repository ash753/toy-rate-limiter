# Step 5. 장애 처리 (Fail-Open + Circuit Breaker) - 요구사항

## 목표

Redis 장애(연결 실패, 타임아웃, 스크립트 오류) 발생 시 서비스가 중단되지 않고 요청을 그대로 통과시킨다(fail-open). Resilience4j Circuit Breaker로 장애 시 불필요한 대기를 차단한다.

## 범위

- Redis 관련 예외 발생 시 fail-open 처리
- Resilience4j Circuit Breaker 적용
- Fail-open 발생 시 WARN 로그

## Fail-Open 범위

- Redis 연결 실패 (`RedisConnectionFailureException`)
- Redis 명령 타임아웃 (`RedisCommandTimeoutException` 및 `.timeout(Duration.ofMillis(500))`에 의한 `TimeoutException`)
- Lua 스크립트 실행 오류
- Circuit Breaker OPEN 상태로 인한 호출 차단 (`CallNotPermittedException`)
- 그 외 `RedisSystemException` 계열

위 상황 발생 시 모두 "허용(allowed=true)"으로 처리하여 서비스 연속성을 보장한다.

## Circuit Breaker 설정

Resilience4j 파라미터 (application.yaml):
- `failureRateThreshold`: 50 (최근 요청 중 50% 이상 실패 시 OPEN)
- `slidingWindowSize`: 10 (최근 10건 기준 실패율 계산)
- `slidingWindowType`: COUNT_BASED
- `waitDurationInOpenState`: 30s (OPEN 후 30초 대기 후 HALF_OPEN)
- `permittedNumberOfCallsInHalfOpenState`: 3 (HALF_OPEN 시 3건 시도 후 판정)
- `minimumNumberOfCalls`: 10 (최소 10건 이상 수집 후 판단)
- 실패로 간주할 예외: 위 Fail-Open 범위와 동일

## 동작 흐름

```
[ProxyWebFilter]
    ↓
[Circuit Breaker wrapped RateLimiter.check()]
    ├─ CLOSED: 정상 호출
    │   ├─ 성공 → RateLimitResult 반환
    │   └─ 실패 (Redis 예외) → fallback: allowed=true 반환 + WARN 로그
    ├─ OPEN: 호출 스킵 → 즉시 fallback: allowed=true
    └─ HALF_OPEN: 제한된 횟수만 시도 → 결과에 따라 CLOSED/OPEN 전환
```

## Fail-Open 시 응답 헤더

- `X-RateLimit-Limit`: policy limit 값
- `X-RateLimit-Remaining`: policy limit 값 (체크 못했으므로 보수적으로 limit 그대로 반환)
- `Retry-After`: 해당 없음 (차단 아님)

**결정:** fail-open 시 `X-RateLimit-Remaining`은 `limit` 값으로 설정.

## 로깅 정책

- Fail-open 발생 시 `WARN` 레벨 로그:
  ```
  [RateLimit] fail-open due to Redis error. key={}, errorType={}, message={}
  ```
- Circuit Breaker 상태 전이 로그:
  - `CLOSED → OPEN`: WARN
  - `OPEN → HALF_OPEN`: INFO
  - `HALF_OPEN → CLOSED`: INFO

## 검증 기준

- [x] Redis 정지 상태에서 요청 → 200 응답 (요청 통과)
- [x] Redis 정지 + 연속 호출 → Circuit Breaker OPEN 전환 (로그 확인)
- [x] Redis 복구 → HALF_OPEN → CLOSED 복귀
- [x] Fail-open 시 WARN 로그 출력
- [x] Circuit Breaker 설정 파라미터가 실제 동작에 반영됨

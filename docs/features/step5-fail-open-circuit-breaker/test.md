# Step 5. 장애 처리 (Fail-Open + Circuit Breaker) - 테스트 계획

## 테스트 목표
Redis 장애 상황(연결 실패, 타임아웃 등)에서도 서비스가 중단되지 않고 요청을 허용(Fail-Open)하는지 검증하고, 서킷 브레이커가 의도한 임계치에 따라 상태를 전환하는지 확인한다.

## 테스트 시나리오

### 1. Fail-Open 동작 검증 (단일 장애)
*   **상황:** Redis 서버가 다운되거나 응답이 타임아웃(500ms 초과)되는 경우
*   **기대 결과:**
    *   `RateLimiter.check()` 호출 시 예외가 발생하지 않고 `allowed = true` 결과 반환
    *   `remainCount`는 설정된 `limit` 값과 동일하게 반환 (보수적 처리)
    *   로그에 `[RateLimit] fail-open...` 경고 메시지가 기록됨

### 2. Circuit Breaker 상태 전이 검증 (연속 장애)
*   **상황:** Redis 장애가 지속적으로 발생하여 실패율이 설정된 임계치(예: 50)를 넘는 경우
*   **기대 결과:**
    *   서킷 브레이커 상태가 `CLOSED` -> `OPEN`으로 전환됨
    *   로그에 `[CB] State transition: CLOSED -> OPEN` 기록됨
    *   이후 Redis 호출 없이 즉시 Fail-Open 결과 반환 (서킷에 의한 차단 및 우회)

### 3. Circuit Breaker 복구 검증 (장애 복구)
*   **상황:** 서킷이 `OPEN`된 상태에서 대기 시간(30s)이 지난 후 Redis가 정상 복구된 경우
*   **기대 결과:**
    *   서킷 상태가 `HALF_OPEN`으로 전환되어 일부 요청을 Redis로 전달
    *   성공적인 응답이 확인되면 `CLOSED` 상태로 최종 복구됨

### 4. 정상 경로 회귀 테스트 (No Regression)
*   **상황:** Redis가 정상인 상태에서 처리율 제한 요청
*   **기대 결과:** Step 4에서 구현한 처리율 제한 로직이 Circuit Breaker 도입 후에도 동일하게 동작함

## 테스트 구현 전략

### 단위 테스트 (`RedisRateLimiterFailureTest`)
*   `MockK`를 사용하여 `ReactiveStringRedisTemplate`을 Mocking하고 의도적으로 `RedisConnectionFailureException` 등을 발생시킴
*   `StepVerifier`를 사용하여 비동기 응답(Mono)의 결과값 및 Fail-Open 동작 검증
*   서킷 브레이커의 설정을 테스트용으로 축소하여 상태 전이(`OPEN`)를 빠르게 검증

### 통합 테스트 (`RateLimitFailOpenIntegrationTest`)
*   `Testcontainers`를 활용하여 실제 Redis 컨테이너를 실행 후, `redis.stop()`을 통해 강제로 장애 상황 재현
*   `WebTestClient`를 통해 실제 API 경로로 요청을 보내 HTTP 200 응답과 Fail-Open 헤더 확인

## 검증 체크리스트
- [x] Redis 다운 시 HTTP 200 응답이 오는가? (통합 테스트 완료)
- [x] 응답 헤더 `X-RateLimit-Remaining`이 limit 값으로 채워지는가? (통합 테스트 완료)
- [x] 서킷이 열렸을 때 로그가 정상적으로 남는가? (수동 확인 완료)
- [x] 서킷이 열린 상태에서도 Fail-Open 로직이 동작하는가? (단위 테스트 완료)
- [x] 서킷 브레이커 복구 시나리오 (단위 테스트 완료: OPEN -> HALF_OPEN -> CLOSED 전이 확인)

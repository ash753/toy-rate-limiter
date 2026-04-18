# Step 1. 프로젝트 초기 구성 - 테스트 계획

## 테스트 목적

두 서버가 정상 기동되고, 서버 1 → 서버 2 포워딩이 투명하게 동작하는지 확인.

## 테스트 유형

### 1. 빌드 검증
- `./gradlew build` 성공
- 두 모듈 모두 jar 생성됨

### 2. 기동 검증 (수동)
- `docker-compose up -d redis` → Redis 기동 확인 (`docker ps`)
- `./gradlew :test-api:bootRun` → 8081 포트 LISTEN
- `./gradlew :rate-limiter:bootRun` → 8080 포트 LISTEN
- 양쪽 `/actuator/health`가 `UP` 반환

### 3. 포워딩 통합 테스트 (수동 curl)

| 시나리오 | 요청 | 기대 결과 |
|---|---|---|
| 정상 포워딩 | `curl -i localhost:8080/api/foo` | 200, `{"timestamp":..., "status":200, "message":"OK", "data":"foo"}` |
| 다른 엔드포인트 | `curl -i localhost:8080/api/bar` | 200, `{"timestamp":..., "status":200, "message":"OK", "data":"bar"}` |
| 없는 path | `curl -i localhost:8080/api/none` | 서버 2의 404가 그대로 전달 |
| Query string | `curl -i "localhost:8080/api/foo?x=1"` | 200, 서버 2까지 query 전달 확인 (서버 2 로그로 검증) |

### 4. 자동화 테스트 (Unit Test)

#### 라우팅 우선순위 검증 (`RouteMatcherTest`)
- **테스트 항목**: 설정 파일의 순서와 상관없이 가장 구체적인 패턴이 먼저 매칭되는지 확인.
- **테스트 명령어**: `cd rate-limiter && ./gradlew test --tests "com.ratelimiter.proxy.RouteMatcherTest"`
- **주요 케이스**:
    - `/api/foo` vs `/api/**` -> `/api/foo` 우선 매칭.
    - `/api/**` vs `/**` -> `/api/**` 우선 매칭.

## 제외 사항

- 성능 테스트는 Step 7에서 수행 (Step 1에서는 기능 확인만)
- rate limit 동작은 Step 4 이후 테스트

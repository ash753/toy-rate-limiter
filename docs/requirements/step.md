# 구현 단계 (Step by Step)

`requirements.md`에 정의된 요구사항을 단계별로 구현합니다. 각 단계는 **독립적으로 동작 가능한 상태**에서 마무리하여, 중간에 점검하고 다음 단계로 넘어가도록 구성했습니다.

---

## Step 1. 프로젝트 초기 구성

**목표:** 두 서버 + Redis가 로컬에서 띄워지고, 서로 통신이 되는 상태

### 작업

- [ ] Gradle 멀티 모듈 프로젝트 구성
  - `:rate-limiter` (서버 1)
  - `:test-api` (서버 2)
- [ ] Kotlin + Spring Boot 기본 세팅
  - 서버 1 (rate-limiter): **Spring WebFlux** + Spring Actuator (비동기 게이트웨이)
  - 서버 2 (test-api): Spring Web (MVC) + Spring Actuator
- [ ] `docker-compose.yml` 작성
  - Redis 단일 인스턴스
- [ ] 서버 2: 더미 엔드포인트 1~2개 구현 (`GET /api/foo`, `GET /api/bar` - JSON 반환)
- [ ] 서버 1: WebClient로 서버 2에 요청을 그대로 포워딩하는 최소 구현 (아직 rate limit 없음)
  - 라우팅: 들어온 path/method/query/headers/body를 그대로 서버 2로 릴레이
  - 서버 2 base URL은 `application.yml`에서 주입 (`proxy.target-base-url`)
  - 구현 위치: `WebFilter` 또는 `RouterFunction` 중 하나로 통일 (Step 4에서 `WebFilter`로 확정)

### 검증

- 서버 1 → 서버 2 포워딩이 동작 (`curl localhost:8080/api/foo` → 서버 2의 응답)

---

## Step 2. 설정 구조 + 엔드포인트 매칭

**목표:** `application.yml`에서 엔드포인트별 limit 설정을 읽어 서버 1이 인식

### 작업

- [ ] `rate-limit` 설정 구조 구현 (`@ConfigurationProperties`)
  ```kotlin
  data class RateLimitProperties(
    val defaultLimit: Int,
    val endpoints: List<EndpointPolicy>
  )
  data class EndpointPolicy(val path: String, val limit: Int, val perIp: Boolean)
  ```
- [ ] 엔드포인트 매처 구현 (요청 path → 적용할 policy 찾기, 미등록이면 default)
- [ ] 단위 테스트: 매칭 로직

### 검증

- 설정값을 바꿔 재시작하면 policy가 반영됨 (로그로 확인)

---

## Step 3. Redis 연동 + Lua 스크립트 (핵심 알고리즘)

**목표:** 이동 윈도우 카운터 알고리즘을 Redis에서 정확히 동작시킴

### 작업

- [ ] Spring Data Redis (Lettuce) 설정, 타임아웃 500ms
- [ ] 이동 윈도우 카운터 Lua 스크립트 작성
  - 입력: key prefix, limit, window size(초)
  - 로직: `redis.call('TIME')`로 현재 시각 획득 → 현재 분/이전 분 카운터 조회 → 가중 평균 계산 → 허용/차단 판정 → 허용 시 현재 분 INCR + TTL 설정
  - 반환: `{allowed, remaining, retryAfterSeconds}`
- [ ] `RateLimiter` 서비스 구현 (Lua 스크립트 호출, 결과 파싱)
- [ ] 통합 테스트: Testcontainers로 Redis 띄우고 동작 검증
  - 단일 스레드: limit 초과 시 차단
  - 윈도우 경계: 시간 경과 후 복구

### 검증

- 단위/통합 테스트 모두 통과
- Lua 스크립트가 원자적으로 동작 (멀티 스레드에서 카운팅 정확)

---

## Step 4. Rate Limit WebFilter 통합

**목표:** 서버 1의 모든 요청이 rate limit 체크를 통과해야 서버 2로 포워딩됨

> **구조:** 서버 1은 Spring WebFlux 기반 게이트웨이로, `WebFilter`에서 rate limit 체크 → 통과 시 WebClient로 서버 2에 비동기 포워딩. 동기/블로킹 `OncePerRequestFilter` 대신 **옵션 C (WebFlux + WebFilter)** 를 선택.

### 작업

- [ ] Spring `WebFilter` 구현 (Reactor `Mono` 체인)
  - path → policy 매칭
  - `per-ip: true`이면 `X-Forwarded-For` 첫 번째 IP 추출해 key에 포함 (없으면 `exchange.request.remoteAddress`)
  - Redis 키 포맷: `rl:{path}` 또는 `rl:{path}:{ip}`
  - 허용/차단 모두 응답 헤더 설정: `X-RateLimit-Limit`, `X-RateLimit-Remaining`
  - 차단 시: 429 + `Retry-After` 헤더 + JSON body (`{"error": "too many requests"}`), 체인 중단
  - 허용 시: `chain.filter(exchange)` 호출 → 다음 단계(포워딩 WebFilter/Handler)로 넘김
- [ ] 포워딩 WebFilter/Handler와 순서 정리 (`@Order`로 rate limit WebFilter가 먼저)
  - WebClient를 논블로킹으로 호출하여 `Mono<Void>` 반환
- [ ] 통합 테스트: 엔드포인트별 limit, per-ip on/off 동작 (`WebTestClient` 사용)

### 검증

- 설정된 limit 초과 시 429 반환 (헤더 포함)
- per-ip 엔드포인트는 IP마다 독립 카운팅

---

## Step 5. 장애 처리 (Fail-Open + Circuit Breaker)

**목표:** Redis 장애 시 서비스가 죽지 않고 요청을 통과시킴

### 작업

- [ ] Redis 호출 부분을 try-catch로 감싸 fail-open 처리 + WARN 로그
- [ ] Resilience4j 의존성 추가
- [ ] `@CircuitBreaker`로 Redis 호출 감싸기
  - failureRateThreshold: 50
  - slidingWindowSize: 10
  - waitDurationInOpenState: 30s
  - permittedNumberOfCallsInHalfOpenState: 3
  - fallback 메서드에서 "허용" 결과 반환 + 로깅
- [ ] 통합 테스트: Redis 컨테이너를 의도적으로 중지 → 요청이 200으로 통과

### 검증

- Redis 정지 시 서비스 응답 지속
- Circuit Breaker 상태 전이 동작 (로그로 확인)

---

## Step 6. 모니터링 (Prometheus + Grafana)

**목표:** 주요 메트릭을 수집하고 Grafana에서 조회 가능

### 작업

- [ ] `spring-boot-starter-actuator` + `micrometer-registry-prometheus` 의존성 추가
- [ ] `/actuator/prometheus` 엔드포인트 노출 (인증 없음)
- [ ] 커스텀 메트릭 등록 (Micrometer):
  - `ratelimit_requests_total{endpoint, status}` - 카운터
  - `ratelimit_blocked_total{endpoint}` - 429 카운터
  - `ratelimit_redis_latency` - 히스토그램
  - `ratelimit_failopen_total` - fail-open 발생 카운터
- [ ] `docker-compose.yml`에 Prometheus + Grafana 추가
- [ ] Prometheus `scrape_configs` 설정 (서버 1 타겟)
- [ ] Grafana 기본 대시보드 JSON 작성 (주요 메트릭 패널)

### 검증

- Grafana에서 요청량, 차단율, Redis 지연 확인 가능

---

## Step 7. 부하 테스트 / 검증

**목표:** 실제 동작이 요구사항대로 맞는지 정량 검증

### 작업

- [ ] nGrinder 시나리오 작성
  - 예: 100 req/min 엔드포인트에 150 req/min 투입
  - 이동 윈도우 특성상 "가중 평균 기준 허용률"로 판정
- [ ] 다중 인스턴스 테스트
  - 서버 1을 2개 실행 (포트 분리)
  - 동시 부하 투입 → Redis 전역 카운팅 정확성 확인
- [ ] 결과 리포트 작성 (허용/차단 비율, 에러율, 지연)

### 검증

- 설정 limit 대비 실제 허용률이 예상 범위 내
- 다중 인스턴스에서도 카운팅 정확

---

## Step 8. Kubernetes 배포

**목표:** 로컬 K8s(minikube/kind) 또는 실제 클러스터에 배포

### 작업

- [ ] 각 서버 Dockerfile 작성
- [ ] Kubernetes manifest 작성
  - `rate-limiter` Deployment (replica 2) + Service (LoadBalancer)
  - `test-api` Deployment (replica 2) + Service (ClusterIP)
  - `redis` Deployment/StatefulSet + Service (ClusterIP)
  - `prometheus`, `grafana` (helm 차트 사용)
  - ConfigMap으로 `application.yml` 주입
- [ ] 실제 클라이언트 IP 전달 설정 (`X-Forwarded-For` 보존)
  - rate-limiter Service에 `externalTrafficPolicy: Local` 설정 (LoadBalancer 뒤에서 소스 IP 보존)
  - 또는 Ingress(NGINX 등) 사용 시 `X-Forwarded-For` 헤더가 자동 부착되므로 Ingress 앞단 구성 권장
  - 환경에 따라 `X-Real-IP` 폴백 로직도 고려 (애플리케이션 레벨)
- [ ] 배포 후 엔드 투 엔드 동작 확인

### 검증

- LoadBalancer 엔드포인트로 요청 → 서버 2까지 도달
- 파드 하나 죽여도 서비스 지속 (replica 2)

---

## 구현 순서 요약

```
Step 1: 스켈레톤 (서버 2개 + Redis 연결)
  ↓
Step 2: 설정 구조
  ↓
Step 3: Redis + Lua (핵심 알고리즘) ← 가장 중요
  ↓
Step 4: Filter 통합 (전체 흐름 완성)
  ↓  ← 여기까지 오면 핵심 기능 동작
Step 5: 장애 처리
  ↓
Step 6: 모니터링
  ↓
Step 7: 부하 테스트
  ↓
Step 8: K8s 배포
```

**최소 동작 지점**: Step 4 완료 시점에 rate limiter의 핵심 기능이 완성됩니다. 이후 Step 5~8은 운영 품질을 높이는 단계입니다.

**추천 진행 속도**: Step 1~4를 먼저 완주한 뒤 전체를 돌려보고, Step 5부터는 필요에 따라 우선순위를 조정하세요.

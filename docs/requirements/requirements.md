# 요구사항

# 목적

- 처리율 제한 장치 토이 프로젝트 구현

# 기술 스택

- Kotlin, Spring Boot, Redis, Kubernetes

# 아키텍처

- Spring 서버 2개 존재 (역할이 다른 2개)
  - 서버 1: 처리율 제한 장치 서버 (게이트웨이/프록시 역할)
    - 클라이언트 요청을 받아 rate limit 체크 후 서버 2로 포워딩
    - 포워딩 방식: WebClient 사용
  - 서버 2: 최종 요청을 처리할 테스트용 API 서버, controller에서 바로 JSON 반환
- Redis
  - 처리율 제한 장치에서 사용
- 모니터링 서버
  - Prometheus
  - Grafana

## Kubernetes 배포

- 서버 1 (Rate Limiter): Deployment, replica 2개, LoadBalancer
- 서버 2 (Test API): Deployment, replica 2개, ClusterIP (서버 1에서만 접근)
- Redis: 별도 배포

# 처리율 제한 장치 서버

## 동작

- 클라이언트 요청을 처음 받는다.
- 클라이언트 요청이 처리율 제한을 넘는지 체크
  - 넘는다 -> HTTP 429 응답(Too Many Requests) 반환
  - 넘지 않는다 -> WebClient로 서버 2에 요청 포워딩
- 허용/차단 모두 포함하는 헤더:
  - `X-RateLimit-Remaining`: 남은 요청 수
  - `X-RateLimit-Limit`: 제한 값
- 429 응답에만 포함하는 헤더:
  - `Retry-After`: 재시도까지 남은 시간 (초 단위 정수)
    - **전략:** 다음 윈도우 시작 시점까지 대기하도록 유도하여 클라이언트의 빈번한 재시도를 방지하고 시스템 부하를 최소화함.

## 처리율 제한 방식

- API endpoint 기반
- 엔드포인트마다 다른 limit 설정 가능 (application.yml에서 관리)
- 필요 시 IP 주소별 제한을 엔드포인트별로 유동적으로 추가 가능 (AND 조건)
  - 예: `/api/foo`는 엔드포인트 기반만, `/api/bar`는 엔드포인트 + IP 기반
- IP 추출 시 `X-Forwarded-For` 헤더 우선 사용
- 미등록 엔드포인트는 기본 limit 적용

### application.yml 설정 구조 예시

```yaml
rate-limit:
  default-limit: 200 # 미등록 엔드포인트 기본값 (req/min)
  endpoints:
    - path: /api/foo
      limit: 100 # req/min
      per-ip: false # 엔드포인트 전체 합산
    - path: /api/bar
      limit: 50
      per-ip: true # IP별 각각 50 req/min
```

## 처리율 제한 알고리즘

- 이동 윈도우 카운터 알고리즘 (두 개의 fixed window 카운터를 가중 평균하는 방식)
- 윈도우 크기: 1분
- 제한 단위: 분당 요청 수 (req/min)

### Redis 구현 전략

- 자료구조: 두 개의 fixed window 카운터 (INCR + GET)
- 원자성 보장: Lua 스크립트 사용 (race condition 방지)
- TTL: 윈도우 크기의 2배 (120초) - 이전 윈도우 참조를 위해
- 시간 기준: Redis 서버 시간 (`redis.call('TIME')`)

## 장애 시 동작 (Fail-Open)

- Redis 관련 오류 발생 시 요청을 서버 2로 전달 (fail-open)
- Redis 타임아웃: 500ms
- Circuit Breaker 적용 (Resilience4j)
  - `failureRateThreshold`: 50% (최근 요청 중 절반 이상 실패 시 open)
  - `slidingWindowSize`: 10 (최근 10건 기준으로 실패율 계산)
  - `waitDurationInOpenState`: 30초 (open 후 30초 뒤 half-open으로 전환)
  - `permittedNumberOfCallsInHalfOpenState`: 3 (half-open 시 3건 시도 후 판정)
  - open 상태에서는 Redis 호출을 건너뛰고 즉시 fail-open
  - half-open에서 복구 확인 후 closed로 전환
- fail-open 발생 시 로깅 처리

## 모니터링 메트릭 수집

- Prometheus를 통해 수집하고, Grafana로 조회
- Spring Actuator 사용 (`/actuator/prometheus` 엔드포인트, 인증 없음)
- 수집 메트릭:
  - 총 요청 수 (엔드포인트별, 상태코드별)
  - rate limit 차단(429) 카운터
  - 윈도우별 허용/차단 비율
  - Redis 응답 지연 (latency histogram)
  - Fail-open 발생 횟수
- Grafana 기본 대시보드 템플릿 제공

## 테스트 / 검증

- 부하 테스트: nGrinder 사용
- 단위/통합 테스트: Testcontainers로 Redis 구동
- 동시성 테스트: 여러 인스턴스에서 동시 요청 시 전역 카운팅 정확성 검증
- 성공 기준: 이동 윈도우 카운터 특성상 가중 평균 기반으로 검증

## 429 응답 body

- JSON 에러 포맷 (추후 정의)

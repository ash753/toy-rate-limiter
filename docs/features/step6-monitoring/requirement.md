# Step 6. 모니터링 (Prometheus + Grafana) - 요구사항

## 목표

서비스 상태를 Prometheus로 수집하고 Grafana에서 조회 가능하도록 한다. 주요 메트릭은 rate limiter 동작 품질 파악에 필요한 항목들이다.

## 범위

- Spring Actuator + Micrometer Prometheus registry
- `/actuator/prometheus` 엔드포인트 노출 (인증 없음)
- 커스텀 메트릭 4종 등록
- Prometheus scrape 설정
- Grafana 대시보드 기본 템플릿

## 수집 메트릭

| 메트릭 이름 | 타입 | 라벨 | 설명 |
|---|---|---|---|
| `ratelimit_requests_total` | Counter | `endpoint`, `result` (`allowed`/`blocked`/`failopen`) | 엔드포인트별 처리 결과 |
| `ratelimit_redis_latency_seconds` | Histogram | — | Redis 호출 지연 분포 |
| `ratelimit_failopen_total` | Counter | `reason` (에러 유형) | Fail-open 발생 횟수 |
| `resilience4j_circuitbreaker_state` | Gauge | `state` | Resilience4j 기본 메트릭 활용 (0=CLOSED, 1=OPEN, 2=HALF_OPEN) |

> 참고: "차단 비율", "요청 수"는 `ratelimit_requests_total`에서 PromQL로 파생 가능 (별도 메트릭 불필요).

### 파생 쿼리 예시

```promql
# 전체 차단 비율
sum(rate(ratelimit_requests_total{result="blocked"}[1m]))
  / sum(rate(ratelimit_requests_total[1m]))

# 엔드포인트별 요청량
sum by (endpoint) (rate(ratelimit_requests_total[1m]))

# Redis p95 지연
histogram_quantile(0.95, rate(ratelimit_redis_latency_seconds_bucket[1m]))
```

## Prometheus 수집 정책

- `/actuator/prometheus` 엔드포인트 노출
- 인증 없음 (토이 프로젝트)
- scrape interval: 5초
- 대상: rate-limiter 서버 1 인스턴스들

## Grafana 대시보드 (기본 템플릿)

### 패널 구성 (최소)
1. **요청 처리 현황**: 허용/차단 요청 수 (시계열)
2. **차단 비율**: blocked / total × 100 (게이지 또는 시계열)
3. **엔드포인트별 TOP 5**: 요청량 상위 엔드포인트
4. **Redis 지연 (p50, p95, p99)**: 시계열
5. **Fail-open 발생**: 카운터 증가 추이
6. **Circuit Breaker 상태**: 현재 state 표시

## 결과물

- `rate-limiter`에 Actuator + Micrometer Prometheus + Resilience4j Micrometer 의존성 추가
- `application.yml`에 actuator 노출 및 Resilience4j 메트릭 활성화 설정
- 커스텀 메트릭 등록 코드
- `docker-compose.yml`에 Prometheus + Grafana 추가
- `prometheus.yml` 설정
- `grafana/dashboards/ratelimit.json` 대시보드 JSON

## 검증 기준

- `curl localhost:8080/actuator/prometheus`로 메트릭 조회 가능
- 요청 발생 시 `ratelimit_requests_total` 카운터 증가 확인
- Grafana에서 대시보드 import → 데이터 표시됨
- Circuit Breaker OPEN 시 해당 메트릭이 변화

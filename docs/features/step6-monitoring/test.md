# Step 6. 모니터링 (Prometheus + Grafana) - 테스트 계획

## 테스트 목적

- 커스텀 메트릭이 정상 노출
- Prometheus가 메트릭을 스크레이핑
- Grafana에서 시각화 가능

## 테스트 유형

### 1. Actuator 엔드포인트 테스트

```kotlin
@SpringBootTest(webEnvironment = RANDOM_PORT)
class ActuatorEndpointTest {
  @Test
  fun `prometheus endpoint exposes metrics`() {
    client.get().uri("/actuator/prometheus")
      .exchange()
      .expectStatus().isOk
      .expectBody(String::class.java)
      .value { body ->
        assertThat(body).contains("ratelimit_requests_total")
        assertThat(body).contains("ratelimit_redis_latency_seconds")
        assertThat(body).contains("resilience4j_circuitbreaker_state")
      }
  }
}
```

### 2. 메트릭 증가 검증

`MeterRegistry`를 주입받아 값 확인.

| # | 시나리오 | 기대 결과 |
|---|---|---|
| 1 | 허용 요청 1건 | `ratelimit_requests_total{endpoint="/api/**",result="allowed"}` +1 |
| 2 | 차단 요청 1건 | `ratelimit_requests_total{endpoint="/api/**",result="blocked"}` +1 |
| 3 | Fail-open 1건 | `ratelimit_failopen_total{reason="..."}` +1 + `ratelimit_requests_total{endpoint="/api/**",result="failopen"}` +1 |
| 4 | Redis 호출 | `ratelimit_redis_latency_seconds_count` 증가, histogram buckets 채워짐 |

> 주의: `endpoint` 라벨은 실제 요청 경로(`/api/foo`)가 아니라 매칭된 패턴(`/api/**`)이어야 함.

```kotlin
@Test
fun `allowed request increments counter with pattern tag`() {
  val before = registry.counter("ratelimit.requests",
    "endpoint", "/api/**", "result", "allowed").count()

  client.get().uri("/api/foo").exchange().expectStatus().isOk

  val after = registry.counter("ratelimit.requests",
    "endpoint", "/api/**", "result", "allowed").count()

  assertThat(after - before).isEqualTo(1.0)
}
```

### 3. Resilience4j 메트릭 확인

- `resilience4j_circuitbreaker_state{name="redisRateLimiter",state="closed"}` 존재 확인
- Redis 정지 → state="open" 메트릭 값이 1로 전환되는지 확인

### 4. Prometheus scrape 검증 (수동)

- `docker-compose up -d prometheus` 실행 후
- `localhost:9090/targets` 에서 rate-limiter 상태가 `UP`인지 확인
- Prometheus UI에서 `ratelimit_requests_total` 쿼리 수행

### 5. Grafana 대시보드 검증 (수동)

- `localhost:3000` 접속 (admin/admin)
- 프로비저닝된 대시보드 자동 로드 확인
- 부하 발생 시 패널의 그래프 변화 확인

### 6. 카디널리티 검증

- 여러 개의 다른 path(예: `/api/1`, `/api/2`, `/api/3`)로 요청을 보낸 후
- `ratelimit_requests_total`의 `endpoint` 라벨이 모두 `/api/**`로 단일화되어 기록되는지 확인

## 샘플 테스트 (메트릭 주입)

```kotlin
@SpringBootTest
@AutoConfigureWebTestClient
class MetricsIntegrationTest {
  @Autowired lateinit var registry: MeterRegistry
  @Autowired lateinit var client: WebTestClient

  @Test
  fun `metrics reflect actual requests`() {
    repeat(5) { client.get().uri("/api/foo").exchange() }

    val allowed = registry.find("ratelimit.requests")
      .tag("endpoint", "/api/**") // 패턴으로 태깅됨
      .tag("result", "allowed")
      .counter()

    assertThat(allowed?.count()).isGreaterThanOrEqualTo(5.0)
  }
}
```

## 제외 사항

- 알림(Alertmanager) 설정은 범위 밖
- 장기 보관/고가용성 Prometheus는 범위 밖
- Grafana 대시보드 JSON 문법 검증은 수동으로 처리

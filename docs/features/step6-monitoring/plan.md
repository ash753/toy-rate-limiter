# Step 6. 모니터링 (Prometheus + Grafana) - 실행 계획

## 작업 순서

### 1. 의존성 추가

`rate-limiter/build.gradle.kts`:
```kotlin
implementation("org.springframework.boot:spring-boot-starter-actuator")
implementation("io.micrometer:micrometer-registry-prometheus")
implementation("io.github.resilience4j:resilience4j-micrometer:2.2.0")
```

### 2. 데이터 모델 업데이트

`RateLimitResult.kt`:
```kotlin
data class RateLimitResult(
    val allowed: Boolean,
    val remainCount: Int,
    val retryAfterSeconds: Int,
    val isFailOpen: Boolean = false
)
```

### 3. Actuator 및 Resilience4j 설정

`application.yml`:
```yaml
management:
  endpoints:
    web:
      exposure:
        include:
          - health
          - info
          - prometheus
          - metrics
  endpoint:
    prometheus:
      enabled: true
  prometheus:
    metrics:
      export:
        enabled: true
  metrics:
    distribution:
      percentiles-histogram:
        ratelimit.redis.latency: true
      percentiles:
        ratelimit.redis.latency: 0.5, 0.95, 0.99

resilience4j:
  circuitbreaker:
    metrics:
      enabled: true
```

### 4. 커스텀 메트릭 등록

`RateLimitMetrics.kt`:
```kotlin
@Component
class RateLimitMetrics(private val registry: MeterRegistry) {

    fun recordRequest(endpoint: String, result: String) {
        Counter.builder("ratelimit.requests")
            .tag("endpoint", endpoint)
            .tag("result", result)
            .register(registry)
            .increment()
    }

    fun recordFailopen(reason: String) {
        Counter.builder("ratelimit.failopen")
            .tag("reason", reason)
            .register(registry)
            .increment()
    }

    val redisLatency: Timer = Timer.builder("ratelimit.redis.latency")
        .publishPercentileHistogram()
        .register(registry)
}
```

### 5. 메트릭 적용 지점

#### RedisSlidingWindowRateLimiter (지연 시간 및 Fail-open)
```kotlin
override fun check(keyPrefix: String, limit: Int): Mono<RateLimitResult> {
    val sample = Timer.start()
    return redisTemplate.execute(...)
        .single()
        .map { result -> 
            // ... 변환 로직
            RateLimitResult(allowed, remain, retry) 
        }
        .doFinally { sample.stop(metrics.redisLatency) }
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
        .onErrorResume { t ->
            metrics.recordFailopen(t.javaClass.simpleName)
            Mono.just(RateLimitResult(allowed = true, remainCount = limit, retryAfterSeconds = 0, isFailOpen = true))
        }
}
```

#### RateLimitWebFilter (요청 결과 기록)
- `endpoint` 라벨로 `route.pathPattern`을 사용하여 cardinality 폭발 방지.
```kotlin
return rateLimiter.check(keyPrefix, limit)
    .flatMap { result ->
        val resultLabel = when {
            result.isFailOpen -> "failopen"
            result.allowed -> "allowed"
            else -> "blocked"
        }
        metrics.recordRequest(route.pathPattern, resultLabel)
        // ... 후속 처리
    }
```

### 6. docker-compose에 Prometheus + Grafana 추가

```yaml
services:
  prometheus:
    image: prom/prometheus:latest
    volumes:
      - ./monitoring/prometheus.yml:/etc/prometheus/prometheus.yml
    ports:
      - "9090:9090"

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    environment:
      GF_SECURITY_ADMIN_PASSWORD: admin
    volumes:
      - ./monitoring/grafana/provisioning:/etc/grafana/provisioning
```

### 7. prometheus.yml

```yaml
global:
  scrape_interval: 5s

scrape_configs:
  - job_name: 'rate-limiter'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8080']
```

## 예상 파일

```
toy-rate-limiter/
├── monitoring/
│   ├── prometheus.yml
│   └── grafana/
│       └── provisioning/ ...
├── rate-limiter/src/main/kotlin/.../metrics/
│   └── RateLimitMetrics.kt
└── docker-compose.yml (업데이트)
```

## 완료 조건

- `/actuator/prometheus` 응답에 커스텀 메트릭 및 Resilience4j 메트릭 포함
- Prometheus UI에서 메트릭 조회 가능
- Grafana 대시보드에서 실시간 변화 확인

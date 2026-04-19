# Step 4. Rate Limit WebFilter 통합 - 테스트 계획

## 테스트 목적

- rate limit 체크가 실제 HTTP 요청 플로우에 올바르게 통합됨을 확인.
- 허용/차단 모든 상황에서 `RateLimitConstants`에 정의된 헤더가 정확히 포함되는지 검증.
- `IpExtractor`가 다양한 헤더(`X-Forwarded-For` 등) 상황에서 올바른 IP를 추출하는지 검증.
- 차단 시 실제로 백엔드(서버 2)로 요청이 전달되지 않는지 확인.

## 테스트 유형

### 1. 단위 테스트 (`IpExtractorTest`)

`ServerHttpRequest`를 모킹하여 다양한 케이스 검증.

| # | 케이스 | 입력 헤더 | 기대 결과 |
|---|---|---|---|
| 1 | X-Forwarded-For 우선 | `X-Forwarded-For: 1.1.1.1, 2.2.2.2`, `X-Real-IP: 3.3.3.3` | `1.1.1.1` |
| 2 | X-Real-IP 차선 | `X-Real-IP: 3.3.3.3` (XFF 없음) | `3.3.3.3` |
| 3 | Remote Address 최종 | 헤더 모두 없음 | `request.remoteAddress`의 hostAddress |
| 4 | 전부 없음 | 모두 null | `"unknown"` |

### 2. 통합 테스트 (`RateLimitFilterIntegrationTest`)

`@SpringBootTest` + `Testcontainers` (Redis) + `WireMock`.

| # | 케이스 | 시나리오 | 기대 결과 |
|---|---|---|---|
| 1 | 허용 + 헤더 검증 | 첫 요청 시도 | 200 OK, `X-RateLimit-*` 헤더 존재 |
| 2 | 잔여 횟수 감소 확인 | 동일 IP로 연속 요청 | `X-RateLimit-Remaining` 값이 순차적으로 감소 |
| 3 | 차단 + 바디 검증 | Limit 초과 시도 | 429, `Retry-After`, JSON Body (`too many requests`) |
| 4 | per-ip 모드 격리 | IP A로 초과 후 IP B로 시도 | IP A는 429, IP B는 200 OK |
| 5 | 미등록 경로 패스 | 설정에 없는 path로 요청 | `X-RateLimit-Limit`에 전역 기본값(200) 적용 확인 |

## 테스트 환경 설정 가이드

### WireMock 고정 포트 설정
- 스프링 부트의 리스트 설정 바인딩 유실 문제를 방지하기 위해 WireMock 포트를 `8089`로 고정하고, 테스트 프로퍼티에 `proxy.routes` 전체 목록을 명시하여 테스트를 수행함.

### WebTestClient 수동 빌드
- `AutoConfigureWebTestClient` 어노테이션 이슈를 피하기 위해 `ApplicationContext`를 주입받아 `WebTestClient.bindToApplicationContext(context).build()`로 수동 생성하여 사용함.

### Redis 초기화
- `@BeforeEach`에서 `redisTemplate.connectionFactory.connection.flushAll()`을 호출하여 테스트 간 독립성 보장.

## 제외 사항
- Redis 장애 시의 Fail-Open 동작은 Step 5에서 다룸.
- 상세 메트릭 수집 여부는 Step 6에서 검증.

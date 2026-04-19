# Step 4. Rate Limit WebFilter 통합 - 실행 계획

## 작업 순서

### 1. 공통 유틸리티 및 상수 구현

- `com.ratelimiter.common.RateLimitConstants` 생성: 헤더명, 기본 메시지 등 정의
- `com.ratelimiter.common.IpExtractor` 생성: `ServerHttpRequest`에서 IP 추출 로직 분리

### 2. RateLimitWebFilter 구현

```kotlin
@Component
@Order(1)
class RateLimitWebFilter(
    private val routeMatcher: RouteMatcher,
    private val rateLimiter: RateLimiter,
    private val proxyRoutesProperties: ProxyRoutesProperties,
) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.pathWithinApplication()
        val route = routeMatcher.findMatch(path) ?: return chain.filter(exchange)
        
        val ip = IpExtractor.extract(exchange.request)
        val keyPrefix = if (route.perIp) "rl:${route.pathPattern}:$ip" else "rl:${route.pathPattern}"
        val limit = route.limit ?: proxyRoutesProperties.defaultLimit

        return rateLimiter.check(keyPrefix, limit)
            .flatMap { result ->
                val response = exchange.response
                response.headers.set(RateLimitConstants.HEADER_LIMIT, limit.toString())
                response.headers.set(RateLimitConstants.HEADER_REMAINING, result.remainCount.toString())

                if (result.allowed) {
                    chain.filter(exchange)
                } else {
                    response.statusCode = HttpStatus.TOO_MANY_REQUESTS
                    response.headers.set(HttpHeaders.RETRY_AFTER, result.retryAfterSeconds.toString())
                    response.headers.contentType = MediaType.APPLICATION_JSON
                    
                    val body = RateLimitConstants.TOO_MANY_REQUESTS_BODY.toByteArray()
                    val buffer = response.bufferFactory().wrap(body)
                    response.writeWith(Mono.just(buffer))
                }
            }
    }
}
```

### 3. ProxyWebFilter 순서 조정

- `ProxyWebFilter.kt`의 `@Order(Ordered.HIGHEST_PRECEDENCE)`를 `@Order(2)`로 변경.

### 4. 통합 테스트 환경 구축

- `Testcontainers` (Redis) + `WireMock` 조합.
- `@DynamicPropertySource`를 사용하여 WireMock 포트를 `proxy.routes[].targetUri`에 맞게 동적 설정.

## 예상 파일 구조

```
rate-limiter/src/main/kotlin/com/ratelimiter/
├── common/
│   ├── RateLimitConstants.kt
│   └── IpExtractor.kt
├── proxy/
│   ├── RateLimitWebFilter.kt
│   └── ProxyWebFilter.kt (수정)
```

## 주의사항

### 1. 헤더 설정 타이밍
- `response.writeWith` 호출 이후에는 헤더 수정이 불가능하므로, 반드시 `flatMap` 내부에서 차단 로직 수행 시점에 모든 헤더 설정을 마쳐야 함.

### 2. 예외 처리
- Redis 연동 에러 시 현재는 500 에러를 반환하게 둠. (Step 5에서 Circuit Breaker를 통한 Fail-Open 도입 예정)

## 완료 조건

- 모든 매칭된 요청에 `X-RateLimit-*` 헤더가 포함됨.
- 한도 초과 시 백엔드(서버 2) 호출 없이 즉시 429 반환.
- IP별 독립 카운팅 정상 동작 확인.

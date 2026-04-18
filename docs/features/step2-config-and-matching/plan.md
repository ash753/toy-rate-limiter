# Step 2. 설정 구조 + 엔드포인트 매칭 - 실행 계획

## 작업 순서

### 1. 프로퍼티 클래스 확장

```kotlin
@ConfigurationProperties(prefix = "proxy")
data class ProxyRoutesProperties(
    val defaultLimit: Int = 200,    // 추가: 기본 처리율 제한 수
    val routes: List<Route> = emptyList()
) {
    data class Route(
        val pathPattern: String,
        val targetUri: String,
        val limit: Int? = null,      // 추가: 엔드포인트 전용 제한 수 (null이면 기본값)
        val perIp: Boolean = false   // 추가: IP별 제한 여부
    ) {
        val parsedPattern: PathPattern by lazy {
            PathPatternParser.defaultInstance.parse(pathPattern)
        }
    }
}
```

- `ProxyRoutesProperties`에 `defaultLimit` 필드 추가.
- `Route` 데이터 클래스에 `limit`, `perIp` 필드 추가.
- 각 필드는 `application.yml`의 계층 구조와 매핑됨.

### 2. Validation 추가

- `defaultLimit > 0`, 각 `route.limit` (null이 아닐 경우) `> 0` 인지 검증.
- `@Validated` 어노테이션을 사용하여 바인딩 시점에서 유효성 체크.

### 3. RouteMatcher 컴포넌트 활용

- 기존의 `RouteMatcher.findMatch`는 그대로 사용.
- 반환된 `Route` 객체 내부의 `limit` 값을 확인하여, null이면 `ProxyRoutesProperties.defaultLimit`을 적용하도록 상위 로직에서 처리 (Step 4 필터 연동 시 적용).

### 4. application.yml 설정 확장

`rate-limiter` 모듈의 `application-local.yaml` 등에 구체적인 정책 기입.

```yaml
proxy:
  default-limit: 200
  routes:
    - path-pattern: "/api/foo"
      target-uri: "http://localhost:8081"
      limit: 100
      per-ip: false
    - path-pattern: "/api/bar/**"
      target-uri: "http://localhost:8081"
      limit: 50
      per-ip: true
```

## 파일 구조

```
rate-limiter/src/main/kotlin/com/ratelimiter/
├── config/
│   └── ProxyRoutesProperties.kt
└── proxy/
    └── RouteMatcher.kt
```

## 완료 조건

- 설정 파일의 `limit`과 `per-ip` 설정값이 `ProxyRoutesProperties` 객체로 정상 바인딩됨.
- `RouteMatcher`를 통해 매칭된 `Route`에서 설정된 `limit`과 `perIp` 값을 읽어올 수 있음.
- 중복된 `path-pattern`이나 유효하지 않은 `limit` 값 설정 시 기동 실패로 조기 감지.

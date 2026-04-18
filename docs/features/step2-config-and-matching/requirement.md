# Step 2. 설정 구조 + 엔드포인트 매칭 - 요구사항

## 목표

`application.yml`에서 API 게이트웨이의 라우팅 설정과 각 엔드포인트별 처리율 제한(Rate Limit) 정책을 함께 읽어들이고, 클라이언트의 요청에 맞는 최적의 정책(Route)을 찾는 로직을 구현한다.

## 범위

- `@ConfigurationProperties` 기반 통합 설정 바인딩 (Routing + Rate Limit)
- 요청 경로 매처 (path → Route 찾기)
- 미등록 경로에 대한 `default-limit` 정책 적용

## 설정 구조

```yaml
proxy:
  default-limit: 200          # 미등록 엔드포인트 기본 제한 (req/min)
  routes:
    - path-pattern: "/api/foo"
      target-uri: "http://localhost:8081"
      limit: 100              # 해당 경로 전용 제한
      per-ip: false           # 엔드포인트 전체 합산
    - path-pattern: "/api/bar/**"
      target-uri: "http://localhost:8081"
      limit: 50
      per-ip: true            # IP별 각각 제한
```

## 도메인 모델

```kotlin
data class ProxyRoutesProperties(
  val defaultLimit: Int = 200,
  val routes: List<Route> = emptyList()
)

data class Route(
  val pathPattern: String,
  val targetUri: String,
  val limit: Int?,             // null일 경우 defaultLimit 사용
  val perIp: Boolean = false
)
```

## 동작 규칙

- **매칭 우선순위:** 스프링의 `PathPattern.SPECIFICITY_COMPARATOR`를 사용하여 가장 구체적인 패턴을 우선 매칭한다.
- **정책 결정:** 
  - 매칭되는 `Route`가 있고 `limit`이 설정되어 있으면 해당 값을 사용한다.
  - 매칭되는 `Route`가 있으나 `limit`이 없으면 상위의 `defaultLimit`을 사용한다.
  - 매칭되는 `Route`가 아예 없으면 라우팅을 수행하지 않는다. (또는 기본 라우팅 정책에 따름)
- **유효성 검사:** `limit`이나 `defaultLimit`은 0보다 커야 하며, 중복된 `path-pattern`은 허용하지 않는다.

## 결과물

- `ProxyRoutesProperties` 데이터 클래스 (필드 확장)
- `RouteMatcher` 컴포넌트: 매칭된 `Route` 객체 반환 (내부에 limit 정보 포함)

## 검증 기준

- 설정 파일의 `limit`, `per-ip` 값이 `ProxyRoutesProperties`에 정확히 바인딩됨
- `RouteMatcher`를 통해 매칭된 결과에서 해당 경로의 `limit` 설정을 확인할 수 있음

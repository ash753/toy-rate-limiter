# Step 2. 설정 구조 + 엔드포인트 매칭 - 테스트 계획

## 테스트 목적

`application.yml`의 라우팅 정책과 처리율 제한 정책(limit, perIp)이 올바르게 바인딩되고, 매칭된 라우트 정보에서 해당 정책을 정확히 읽어올 수 있는지 검증.

## 테스트 유형

### 1. 단위 테스트

#### `RouteMatcher` (설정값 포함 매칭 테스트)

| 케이스 | 등록된 설정 | 입력 경로 | 기대 결과 |
|---|---|---|---|
| 특정 경로 전용 제한 적용 | `/api/foo` (limit: 100) | `/api/foo` | `targetUri`와 `limit: 100` 반환 |
| IP별 제한 여부 확인 | `/api/bar` (per-ip: true) | `/api/bar` | `perIp: true` 확인 |
| 기본값 상속 테스트 | `/api/baz` (limit: null) | `/api/baz` | 매칭은 되지만 `limit`은 null (상위 defaultLimit 적용 대상) |

### 2. 프로퍼티 바인딩 테스트

`@SpringBootTest` 등을 사용하여 바인딩 검증:

```kotlin
@Test
fun `ProxyRoutesProperties binds RateLimit fields from yaml`() {
  ApplicationContextRunner()
    .withUserConfiguration(TestConfig::class.java)
    .withPropertyValues(
      "proxy.default-limit=500",
      "proxy.routes[0].path-pattern=/api/**",
      "proxy.routes[0].target-uri=http://localhost:8081",
      "proxy.routes[0].limit=100",
      "proxy.routes[0].per-ip=true"
    )
    .run { ctx ->
      val props = ctx.getBean(ProxyRoutesProperties::class.java)
      assertThat(props.defaultLimit).isEqualTo(500)
      assertThat(props.routes[0].limit).isEqualTo(100)
      assertThat(props.routes[0].perIp).isTrue()
    }
}
```

### 3. Validation 테스트

| 케이스 | 설정 | 기대 결과 |
|---|---|---|
| 음수 개별 limit | `routes[0].limit: 0` | 기동 실패 |

## 샘플 테스트 코드

```kotlin
class ProxyRoutesPropertiesTest {
  @Test
  fun `route configuration should have limit information`() {
    val route = ProxyRoutesProperties.Route(
        pathPattern = "/api/test",
        targetUri = "http://localhost",
        limit = 100,
        perIp = true
    )
    
    assertEquals(100, route.limit)
    assertTrue(route.perIp)
  }
}
```

## 완료 기준

- 매칭된 `Route`에서 `limit`과 `perIp` 정보를 정상적으로 읽을 수 있음.
- `default-limit` 설정이 전역적으로 유효하게 바인딩됨.
- 잘못된 설정 값에 대해 애플리케이션 시작 단계에서 오류가 발생함.

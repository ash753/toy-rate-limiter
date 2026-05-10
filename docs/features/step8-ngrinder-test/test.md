# Step 8. nGrinder 부하 테스트 - 테스트 가이드

## 1. 개요 및 목적
개발된 처리율 제한 장치의 로컬 Kubernetes 인프라 환경에서의 한계 성능(Stress Test)을 측정하여 시스템의 신뢰성과 최대 처리 용량을 확보한다.

## 2. 테스트 환경 구성
- **애플리케이션**: `rate-limiter`, `test-api` (K8s Deployment/Service)
- **인프라**: Redis (Deployment/Service), Prometheus/Grafana (모니터링)
- **테스트 도구**: nGrinder (Controller, Agent)

## 3. 부하 테스트 실행 가이드

### 로컬 환경 최대 성능(Stress Test)
- **대상**: 전체 시스템 (Rate Limiter + Redis)
- **목적**: 시스템 자원 한계에 도달할 때까지 부하를 주어 최대 TPS 및 병목 구간 파악.
- **성공 기준**: 시스템 한계 TPS 측정 및 안정적인 성능 유지 구간 확인.

#### 실행 전 설정 변경 (임계치 제거)
한계 측정을 위해 처리율 제한 정책에 걸리지 않도록 테스트 대상 경로(/api/foo)의 수치를 대폭 상향합니다.
```bash
# 1. k8s/rate-limiter/configmap.yaml 파일에서 limit 수치를 1,000,000으로 수정한 후 적용
kubectl apply -f k8s/rate-limiter/configmap.yaml

# 2. 변경된 설정을 반영하기 위해 Pod 재시작 (롤링 업데이트 발생)
kubectl rollout restart deployment/rate-limiter -n rate-limiter

# 3. 새로운 설정이 적용된 Pod이 모두 정상 배포될 때까지 대기
kubectl rollout status deployment/rate-limiter -n rate-limiter
```

## 4. nGrinder 테스트 스크립트 (Groovy)
성능 측정을 위해 로그 출력을 제거한 최적화된 스크립트를 사용합니다.

```groovy
import net.grinder.script.GTest
import net.grinder.script.Grinder
import net.grinder.plugin.http.HTTPRequest
import net.grinder.plugin.http.HTTPResponse
import HTTPClient.NVPair
import static net.grinder.script.Grinder.grinder

public class TestRunner {
    public static GTest test
    public static HTTPRequest request

    @BeforeProcess
    public static void beforeProcess() {
        test = new GTest(1, "Stress Test")
        request = new HTTPRequest()
        test.record(request)
    }

    @Run
    public void test() {
        String url = "http://rate-limiter:8080/api/foo"
        HTTPResponse response = request.GET(url)

        if (response.statusCode != 200 && response.statusCode != 429) {
            grinder.logger.error("Unexpected Status: " + response.statusCode)
        }
    }
}
```

## 5. 수집 지표 및 검증 포인트

### 수집 지표
- **nGrinder**: `TPS`, `Mean Test Time (MTT)`, `Peak TPS`
- **Grafana**: `ratelimit_requests_total`, `ratelimit_redis_latency`, `JVM CPU/Memory Usage`

### 검증 포인트
- **Peak TPS**: 시스템이 처리 가능한 최대 초당 요청 수.
- **Saturation Point**: TPS 증가가 정체되고 응답 시간이 급증하는 시점 확인.
- **병목 구간**: CPU가 100%에 도달하는지, 혹은 Redis 지연 시간이 급증하는지 분석.

## 6. 증적 자료 체크리스트

| 구분 | 내용 |
| :--- | :--- |
| **nGrinder** | 테스트 완료 후 Summary 리포트 (TPS, 응답 분포) |
| **Grafana** | `Requests per Second` 그래프 (TPS 추이) |
| **Grafana** | `Redis Latency` 히스토그램 |
| **Grafana** | `JVM` 리소스 사용량 추이 (CPU/Memory) |

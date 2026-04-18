package com.ratelimiter.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ProxyRoutesPropertiesTest {

    // application-local.yaml에 의존하지 않는 독립적인 컨텍스트 러너
    // ValidationAutoConfiguration을 추가하여 @Validated가 작동하도록 구성
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration::class.java))
        .withUserConfiguration(Config::class.java)

    @EnableConfigurationProperties(ProxyRoutesProperties::class)
    class Config

    @Test
    fun `should bind RateLimit fields correctly from isolated properties`() {
        contextRunner.withPropertyValues(
            "proxy.default-limit=500",
            "proxy.routes[0].path-pattern=/api/**",
            "proxy.routes[0].target-uri=http://localhost:8081",
            "proxy.routes[0].limit=100",
            "proxy.routes[0].per-ip=true"
        ).run { context ->
            val props = context.getBean(ProxyRoutesProperties::class.java)
            assertThat(props.defaultLimit).isEqualTo(500)
            assertThat(props.routes).hasSize(1)
            assertThat(props.routes[0].pathPattern).isEqualTo("/api/**")
            assertThat(props.routes[0].targetUri).isEqualTo("http://localhost:8081")
            assertThat(props.routes[0].limit).isEqualTo(100)
            assertThat(props.routes[0].perIp).isTrue()
        }
    }

    @Test
    fun `should fail validation when defaultLimit is less than 1`() {
        contextRunner.withPropertyValues(
            "proxy.default-limit=0" // 유효하지 않은 값
        ).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().hasMessageContaining("defaultLimit must be greater than 0")
        }
    }

    @Test
    fun `should fail validation when route limit is less than 1`() {
        contextRunner.withPropertyValues(
            "proxy.routes[0].path-pattern=/api/foo",
            "proxy.routes[0].target-uri=http://localhost:8081",
            "proxy.routes[0].limit=-5" // 유효하지 않은 값
        ).run { context ->
            assertThat(context).hasFailed()
        }
    }

    @Test
    fun `should fail validation when duplicate path patterns exist`() {
        contextRunner.withPropertyValues(
            "proxy.routes[0].path-pattern=/api/foo",
            "proxy.routes[0].target-uri=http://localhost:8081",
            "proxy.routes[1].path-pattern=/api/foo",
            "proxy.routes[1].target-uri=http://localhost:8082"
        ).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().hasMessageContaining("Duplicate path patterns found")
        }
    }
}

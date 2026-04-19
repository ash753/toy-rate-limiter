package com.ratelimiter.config

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

@Validated
@ConfigurationProperties(prefix = "proxy")
data class ProxyRoutesProperties(
    @field:Min(1)
    val defaultLimit: Int = 200,
    @field:Min(1)
    val windowSize: Int = 60,
    @field:Min(1)
    val ttl: Int = 120,
    @field:Valid
    val routes: List<Route> = emptyList(),
) {
    init {
        require(defaultLimit > 0) { "defaultLimit must be greater than 0" }
        val duplicates = routes.groupBy { it.pathPattern }.filter { it.value.size > 1 }
        require(duplicates.isEmpty()) { "Duplicate path patterns found: ${duplicates.keys}" }
    }

    data class Route(
        @field:NotBlank
        val pathPattern: String,
        @field:NotBlank
        val targetUri: String,
        @field:Min(1)
        val limit: Int? = null,
        val perIp: Boolean = false,
    ) {
        val parsedPattern: PathPattern by lazy {
            PathPatternParser.defaultInstance.parse(pathPattern)
        }
    }
}

package com.ratelimiter.common

import org.springframework.http.HttpStatus
import java.time.ZoneId
import java.time.ZonedDateTime

data class ApiResponse<T>(
    val timestamp: ZonedDateTime = ZonedDateTime.now(ZoneId.of(Constants.UTC)),
    val status: Int,
    val message: String,
    val data: T? = null
) {
    companion object {
        fun <T> success(data: T): ApiResponse<T> = ApiResponse(
            status = HttpStatus.OK.value(),
            message = "OK",
            data = data
        )

        fun success(): ApiResponse<Unit> = ApiResponse(
            status = HttpStatus.OK.value(),
            message = "OK"
        )

        fun <T> error(status: Int, message: String): ApiResponse<T> = ApiResponse(
            status = status,
            message = message
        )
    }
}

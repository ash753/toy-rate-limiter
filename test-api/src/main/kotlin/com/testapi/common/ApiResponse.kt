package com.testapi.common

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
            status = 200,
            message = "OK",
            data = data
        )

        fun success(): ApiResponse<Unit> = ApiResponse(
            status = 200,
            message = "OK"
        )
    }
}

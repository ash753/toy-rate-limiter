package com.testapi.controller

import com.testapi.common.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class SampleController {

    @GetMapping("/foo")
    fun getFoo(): ApiResponse<String> {
        return ApiResponse.success("foo")
    }

    @GetMapping("/bar")
    fun getBar(): ApiResponse<String> {
        return ApiResponse.success("bar")
    }
}

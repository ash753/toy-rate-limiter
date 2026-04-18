package com.testapi

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TestApiApplication

fun main(args: Array<String>) {
    runApplication<TestApiApplication>(*args)
}

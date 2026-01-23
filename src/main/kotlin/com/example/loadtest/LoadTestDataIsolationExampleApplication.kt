package com.example.loadtest

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class LoadTestDataIsolationExampleApplication

fun main(args: Array<String>) {
	runApplication<LoadTestDataIsolationExampleApplication>(*args)
}

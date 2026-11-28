package com.upcpocket.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class PocketBackendApplication

fun main(args: Array<String>) {
    runApplication<PocketBackendApplication>(*args)
}

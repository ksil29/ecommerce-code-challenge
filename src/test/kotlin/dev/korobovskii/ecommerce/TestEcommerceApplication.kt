package dev.korobovskii.ecommerce

import org.springframework.boot.fromApplication
import org.springframework.boot.with

fun main(args: Array<String>) {
    fromApplication<EcommerceApplication>().with(TestcontainersConfiguration::class).withAdditionalProfiles("local").run(*args)
}

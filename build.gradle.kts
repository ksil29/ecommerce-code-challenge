buildscript {
    dependencies {
        classpath("org.codehaus.plexus:plexus-xml:4.2.0")
    }
}

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("io.gatling.gradle") version "3.16.0"
    id("dev.detekt") version "2.0.0-alpha.6"
    id("org.jetbrains.kotlinx.kover") version "0.9.11"
    id("org.cyclonedx.bom") version "3.5.1"
}

group = "dev.korobovskii"
version = "0.0.1-SNAPSHOT"
description = "E-commerce code challenge"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

extra["kotlin.version"] = "2.4.20"
extra["tomcat.version"] = "11.0.27"
extra["jackson-bom.version"] = "3.1.7"
extra["jackson-2-bom.version"] = "2.21.7"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")
    implementation("org.webjars:swagger-ui:5.33.2")
    implementation("org.apache.commons:commons-csv:1.14.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("net.javacrumbs.shedlock:shedlock-spring:7.10.1")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.10.1")
    implementation("com.bucket4j:bucket4j_jdk17-core:8.21.0")
    implementation("com.github.ben-manes.caffeine:caffeine")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-liquibase-test")
    testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-session-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion("2.4.10")
    }
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(file("config/detekt/detekt.yml"))
}

kover {
    reports {
        filters {
            excludes {
                classes("dev.korobovskii.ecommerce.EcommerceApplicationKt")
                packages("shop")
            }
        }
        verify {
            rule {
                minBound(85)
            }
        }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        allWarningsAsErrors = true
    }
}

tasks.cyclonedxDirectBom {
    includeConfigs = listOf("runtimeClasspath")
}

tasks.bootJar {
    archiveFileName = "app.jar"
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileGatlingKotlin") {
    compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
}

tasks.named<JavaCompile>("compileGatlingJava") {
    options.release = 17
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

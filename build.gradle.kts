import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.spring") version "2.2.21" apply false
    kotlin("plugin.jpa") version "2.2.21" apply false
    kotlin("plugin.allopen") version "2.2.21" apply false
    id("org.springframework.boot") version "3.5.16" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.diffplug.spotless") version "7.2.1"
}

allprojects {
    group = "dev.hotelbooking"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    plugins.withId("io.spring.dependency-management") {
        extensions.configure<DependencyManagementExtension> {
            imports { mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.0.3") }
        }
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property") }
        }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
    }
}

spotless {
    kotlin {
        target("**/src/**/*.kt")
        ktlint("1.7.1")
    }
    kotlinGradle {
        target("*.gradle.kts", "*/build.gradle.kts")
        ktlint("1.7.1")
    }
}

tasks.register("systemTest") {
    group = "verification"
    dependsOn(":verification:systemTest")
}

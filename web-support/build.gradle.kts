plugins {
    kotlin("jvm")
    `java-library`
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports { mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.16") }
}

extra["kotlin.version"] = "2.2.21"

dependencies {
    api("org.springframework.security:spring-security-oauth2-jose")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

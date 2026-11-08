plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
}

application { mainClass.set("dev.hotelbooking.verification.DemoSeedKt") }

tasks.register<JavaExec>("systemTest") {
    group = "verification"
    description = "Run isolated Docker Compose acceptance tests and always clean up"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.hotelbooking.verification.SystemTestKt")
    workingDir(rootProject.projectDir)
    if (providers.gradleProperty("systemTestFailAfterStart").isPresent) args("--fail-after-start")
}

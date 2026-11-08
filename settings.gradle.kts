pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}

rootProject.name = "hotel-booking-system"
include("hotel-service", "booking-service", "api-gateway", "discovery-server")

include("web-support")

include("verification")

include("booking-core", "hotel-core")

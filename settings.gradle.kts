pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://repo.spring.io/milestone") }
    }
}

rootProject.name = "flowforge"

include(":libs:common")
include(":libs:test-fixtures")
include(":services:api")
include(":services:orchestrator")

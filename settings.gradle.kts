pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://repo.spring.io/milestone") }
    }
}

rootProject.name = "flowforge"

include(":libs:common")
include(":libs:code-parser")
include(":libs:log-parser")
include(":libs:topology")
include(":libs:graph")
include(":libs:anomaly")
include(":libs:ingest")
include(":libs:test-fixtures")
include(":services:api")
include(":services:orchestrator")

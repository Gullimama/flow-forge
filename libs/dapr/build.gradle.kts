plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${rootProject.libs.versions.spring.boot.get()}")
    }
}

dependencies {
    implementation(project(":libs:common"))
    implementation(libs.dapr.sdk)
    implementation(libs.spring.boot.starter.web)
    implementation("com.fasterxml.jackson.core:jackson-databind:${rootProject.libs.versions.jackson.get()}")
    implementation(libs.spring.boot.starter.actuator)

    testImplementation(libs.spring.boot.starter.test)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}


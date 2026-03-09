plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${rootProject.libs.versions.spring.boot.get()}")
        mavenBom("org.springframework.ai:spring-ai-bom:${rootProject.libs.versions.spring.ai.get()}")
    }
}

dependencies {
    implementation(project(":libs:common"))
    implementation(libs.spring.ai.openai)
    implementation(libs.spring.ai.ollama)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.resilience4j.spring.boot3)
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.retry)

    testImplementation(libs.spring.boot.starter.test)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

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
    implementation(project(":libs:code-parser"))
    implementation(project(":libs:embedding"))
    implementation(libs.javaparser.core)
    implementation(libs.spring.ai.openai)
    implementation(libs.djl.api)
    implementation(libs.djl.onnxruntime)
    implementation(libs.spring.boot.starter.actuator)

    testImplementation(libs.spring.boot.starter.test)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

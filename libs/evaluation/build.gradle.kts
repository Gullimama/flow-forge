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
    implementation(project(":libs:retrieval"))
    implementation(project(":libs:synthesis"))
    implementation(project(":libs:embedding"))
    implementation(project(":libs:vector-store"))
    implementation(project(":libs:flow-builder"))
    implementation(project(":libs:mlflow"))
    implementation(libs.spring.ai.openai)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(project(":libs:code-parser"))
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}


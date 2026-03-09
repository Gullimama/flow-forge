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
    implementation(project(":libs:graph"))
    implementation(project(":libs:mlflow"))
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

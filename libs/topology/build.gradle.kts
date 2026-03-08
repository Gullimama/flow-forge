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
    api(project(":libs:common"))
    implementation(libs.fabric8.kubernetes.client)
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit5)
    testImplementation(libs.testcontainers.minio)
}

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
    implementation(project(":libs:log-parser"))
    implementation(project(":libs:mlflow"))
    implementation(libs.smile.core)

    testImplementation(libs.spring.boot.starter.test)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

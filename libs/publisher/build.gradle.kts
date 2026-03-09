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
    implementation(project(":libs:synthesis"))
    implementation(libs.freemarker)
    implementation(libs.spring.boot.starter.actuator)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(project(path = ":libs:synthesis", configuration = "testOutput"))
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

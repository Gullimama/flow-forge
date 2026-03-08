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
    implementation(project(":libs:code-parser"))
    implementation(project(":libs:log-parser"))
    implementation(project(":libs:topology"))
    implementation(libs.neo4j.java.driver)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit5)
    testImplementation(libs.testcontainers.neo4j)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

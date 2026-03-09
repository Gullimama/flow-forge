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
    implementation(project(":libs:llm"))
    implementation(project(":libs:retrieval"))
    implementation(project(":libs:flow-builder"))
    implementation(libs.spring.boot.starter.actuator)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(project(":libs:code-parser"))
    testImplementation(project(":libs:pattern-mining"))
    testImplementation(project(":libs:anomaly"))
}

val testJar = tasks.register<Jar>("testJar") {
    archiveClassifier.set("test")
    from(sourceSets["test"].output)
}
configurations.register("testOutput") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts {
    add("testOutput", testJar)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

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
    implementation(project(":libs:topology"))
    // SPMF is not on Maven Central. Download spmf.jar into libs/spmf/ (see libs/spmf/README.md).
    // Optional: used at runtime via reflection so the project compiles without the JAR.
    val spmfJar = rootProject.file("libs/spmf/spmf.jar")
    if (spmfJar.exists()) {
        runtimeOnly(files(spmfJar))
    }

    testImplementation(libs.spring.boot.starter.test)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

tasks.register<Test>("patternMiningIntegrationTest") {
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
}

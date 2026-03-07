plugins {
    java
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

allprojects {
    group = "com.flowforge"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven { url = uri("https://repo.spring.io/milestone") }
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "checkstyle")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters"))
    }

    dependencies {
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        // Override global ~/.testcontainers.properties that points ryuk to
        // deprecated quay.io v1 image format
        environment("TESTCONTAINERS_RYUK_DISABLED", "true")
    }

    tasks.test {
        useJUnitPlatform {
            excludeTags("integration")
        }
        maxParallelForks = Runtime.getRuntime().availableProcessors()
    }

    tasks.register<Test>("integrationTest") {
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform {
            includeTags("integration")
        }
        shouldRunAfter(tasks.test)
        maxParallelForks = 1
    }

    // Checkstyle: only run for projects with main source; use root config
    tasks.named("checkstyleMain").configure {
        onlyIf { project.file("src/main/java").exists() }
    }
    extensions.getByType<org.gradle.api.plugins.quality.CheckstyleExtension>().apply {
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        configDirectory.set(rootProject.file("config/checkstyle"))
    }

}

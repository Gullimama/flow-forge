plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":libs:common"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation("jakarta.validation:jakarta.validation-api")
    implementation("org.hibernate.validator:hibernate-validator")
    implementation("com.fasterxml.jackson.core:jackson-databind:${rootProject.libs.versions.jackson.get()}")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:${rootProject.libs.versions.jackson.get()}")
    implementation(libs.fabric8.kubernetes.client)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation("org.wiremock:wiremock-standalone:3.9.1")
}

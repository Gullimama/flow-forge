plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:ingest"))
    implementation(project(":libs:flow-builder"))
    implementation(project(":libs:synthesis"))
    implementation(project(":libs:publisher"))
    implementation(project(":libs:mlflow"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation("jakarta.validation:jakarta.validation-api")
    implementation("org.hibernate.validator:hibernate-validator")

    testImplementation(libs.spring.boot.starter.test)
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient")
    testImplementation(libs.testcontainers.junit5)
    testImplementation(libs.testcontainers.postgresql)
}

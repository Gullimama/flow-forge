plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":libs:common"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.fabric8.kubernetes.client)

    testImplementation(libs.spring.boot.starter.test)
}

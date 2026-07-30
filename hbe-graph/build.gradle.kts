plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":hbe-api"))
    implementation(libs.kotlin.stdlib)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.mockk)
}

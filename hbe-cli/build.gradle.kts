plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.hbe.cli.HbeCliKt")
}

dependencies {
    implementation(project(":hbe-api"))
    implementation(project(":hbe-core"))
    implementation(project(":hbe-infra"))
    implementation(project(":hbe-sdk"))
    implementation(project(":hbe-diagnostics"))
    implementation(project(":hbe-resources"))
    implementation(project(":hbe-compiler"))
    implementation(project(":hbe-dex"))
    implementation(project(":hbe-packager"))
    implementation(project(":hbe-signer"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.kotlin)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.mockk)
}

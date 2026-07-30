plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":hbe-api"))
    implementation(project(":hbe-core"))
    implementation(project(":hbe-infra"))
    implementation(project(":hbe-graph"))
    implementation(project(":hbe-cache"))
    implementation(project(":hbe-memory"))
    implementation(project(":hbe-scheduler"))
    implementation(project(":hbe-sdk"))
    implementation(project(":hbe-dependency"))
    implementation(project(":hbe-resources"))
    implementation(project(":hbe-compiler"))
    implementation(project(":hbe-dex"))
    implementation(project(":hbe-packager"))
    implementation(project(":hbe-signer"))
    implementation(project(":hbe-diagnostics"))
    implementation(project(":hbe-recovery"))
    implementation(project(":hbe-plugins"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.junit.jupiter.api)
    implementation(libs.junit.jupiter.params)
    runtimeOnly(libs.junit.jupiter.engine)
    implementation(libs.mockk)
    implementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}

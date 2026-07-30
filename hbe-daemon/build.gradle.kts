plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.hbe.daemon.HbeDaemonKt")
}

dependencies {
    implementation(project(":hbe-api"))
    implementation(project(":hbe-core"))
    implementation(project(":hbe-infra"))
    implementation(project(":hbe-sdk"))
    implementation(project(":hbe-diagnostics"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.test)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.mockk)
}

plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":hbe-api"))
    implementation(project(":hbe-graph"))
    implementation(project(":hbe-scheduler"))
    implementation(project(":hbe-infra"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.coroutines.core)
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.kotlin)

    testImplementation(project(":hbe-cache"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    val gradleHome = File(System.getenv("GRADLE_USER_HOME")
        ?: "${System.getProperty("user.home")}/.gradle")
    val bbAgent = if (gradleHome.exists()) {
        gradleHome.walkTopDown()
            .firstOrNull { it.name.startsWith("byte-buddy-agent-") && it.extension == "jar" }
    } else null
    if (bbAgent != null) {
        jvmArgs("-javaagent:${bbAgent.absolutePath}", "-Djdk.attach.allowAttachSelf=true")
    }
}


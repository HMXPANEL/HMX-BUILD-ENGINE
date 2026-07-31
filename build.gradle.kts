plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

group = "com.hbe"
version = "1.0.0-SNAPSHOT"

allprojects {
    repositories {
        mavenCentral()
        google()
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = listOf(
                "-Xjsr305=strict",
                "-progressive"
            )
        }
    }

    tasks.withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.encoding = "UTF-8"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

tasks.register("cleanAll") {
    dependsOn(subprojects.map { it.tasks.named("clean") })
}

tasks.register("checkAll") {
    dependsOn(subprojects.map { it.tasks.named("check") })
}

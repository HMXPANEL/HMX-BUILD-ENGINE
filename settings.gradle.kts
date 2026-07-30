rootProject.name = "hbe"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
    ":hbe-api",
    ":hbe-core",
    ":hbe-infra",
    ":hbe-graph",
    ":hbe-cache",
    ":hbe-memory",
    ":hbe-scheduler",
    ":hbe-sdk",
    ":hbe-dependency",
    ":hbe-resources",
    ":hbe-compiler",
    ":hbe-dex",
    ":hbe-packager",
    ":hbe-signer",
    ":hbe-diagnostics",
    ":hbe-recovery",
    ":hbe-plugins",
    ":hbe-cli",
    ":hbe-daemon",
    ":hbe-tests"
)

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

# Compatibility Gap Analysis: HMX vs HMx-assistant-main

| Requirement | Project Needs | HMX Supports | Status | HMX Component |
|-------------|---------------|--------------|--------|---------------|
| Kotlin source | 30+ .kt files | Partial (method exists, untested) | **FAIL** | SourceCompilerImpl |
| Kotlin 2.2.10 compiler | Required | Not installed | **FAIL** | SourceCompilerImpl |
| Jetpack Compose | buildFeatures.compose=true | No Compose compiler plugin | **FAIL** | SourceCompilerImpl |
| Compose BOM 2025.06.00 | Version resolution | N/A without Kotlin | **FAIL** | DependencyResolver |
| compileSdk 36 | Required | Only android-34 installed | **FAIL** | SdkManager |
| targetSdk 36 | Required | Only android-34 installed | **FAIL** | Pipeline |
| Version catalog (libs.toml) | All deps defined | Not parsed | **FAIL** | ProjectImporter |
| Kotlin DSL (.kts) | All build files | Partial regex | **FAIL** | ProjectImporter |
| Release signing | keystore-based | Debug only | **FAIL** | SignerImpl |
| androidx.compose.* | Many AARs | Would merge if Kotlin worked | BLOCKED | ManifestMerger |
| okhttp 5.3.2 | JAR dependency | Would resolve | OK | DependencyManager |
| gson 2.13.2 | JAR dependency | Would resolve | OK | DependencyManager |
| coroutines 1.10.2 | JAR dependency | Would resolve | OK | DependencyManager |
| coil 3.3.0 | AAR dependency | Would merge | OK | ManifestMerger |
| security-crypto | AAR dependency | Would merge | OK | ManifestMerger |
| haze 1.0.1 | AAR dependency (custom) | Would merge | OK | ManifestMerger |
| permissions (9) | Standard Android | Supported | OK | ManifestMerge |
| activities/services/receivers | Standard Android | Supported | OK | ManifestMerge |
| tools namespace | xmlns:tools used | Supported | OK | ManifestMerge |
| meta-data in service | accessibility service | Supported | OK | ManifestMerge |
| minSdk 24 | Supported | Supported | OK | GradleMetadata |
| Java 11 compat | Source level | Not configurable | WARN | SourceCompilerImpl |

## Critical Blockers (build cannot succeed)

1. **No Kotlin compiler** — The project is 100% Kotlin. HMX has no working kotlinc integration.
2. **No Compose compiler plugin** — Compose requires `org.jetbrains.kotlin.plugin.compose`. Without it, Compose code cannot compile.
3. **compileSdk 36 not installed** — HMX only has android-34.
4. **Version catalog not parsed** — `libs.versions.toml` defines all dependencies; HMX can't read it.

## Workaround Options

| Option | Feasibility |
|--------|-------------|
| Pre-compile Kotlin with external Gradle, then package with HMX | Possible but defeats purpose |
| Use android-34 instead of 36 | Possible (most Compose APIs backward compatible) |
| Parse version catalog | Medium effort |
| Install Kotlin compiler + Compose plugin | High effort |
| Install android-36 platform | Medium effort (download) |

## Classification

**C) ENGINE NOT READY** for this project.

The project requires Kotlin + Compose compilation, which HMX fundamentally does not support. Even if all dependencies were resolved and manifests merged, the Kotlin/Compose source code cannot be compiled by the current engine.

To make this project buildable, HMX would need:
1. Kotlin compiler integration (kotlinc or KSP)
2. Compose compiler plugin support
3. android-36 platform
4. Version catalog parsing
5. Release signing support

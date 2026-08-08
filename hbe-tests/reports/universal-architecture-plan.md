# HMX Universal Build Engine — Technical Implementation Plan

**Goal:** Support Java + Kotlin + Compose while keeping existing Java pipeline intact.

---

## Target Architecture

```
Project Scan
  → Detect languages (Java/Kotlin)
  → Detect UI type (XML/Compose)
  → Parse build files (.gradle + .gradle.kts)
  → Parse version catalog (libs.versions.toml)
  → Resolve dependencies (Maven/AAR/JAR)
  → Resolve SDK (compileSdk/targetSdk/minSdk)
  → Merge manifests (app + library)
  → Merge/compile resources (XML)
  → Compile Java sources → .class
  → Compile Kotlin sources → .class (+ Compose plugin if needed)
  → Combined classpath → DEX
  → Package APK
  → Sign (debug/release)
  → Verify
```

## Compilation Paths

```
Java *.java  ──→ javac ──────────────→ .class ─┐
                                               ├──→ DEX → APK
Kotlin *.kt ──→ kotlinc (+compose) ──→ .class ─┘
```

Both paths remain separate and compatible. Mixed projects compile both.

---

## Phase A: Kotlin Toolchain

### A1. Install Kotlin Compiler
- Download kotlin-compiler-<version>.zip from JetBrains releases
- Extract to ~/.hbe/kotlin/<version>/
- Detect version from project (build.gradle.kts kotlin = "2.2.10")

### A2. Kotlin Compiler Detection
- Add KotlinToolchain to SdkManager / standalone detector
- Search order:
  1. ~/.hbe/kotlin/<version>/bin/kotlinc
  2. System kotlinc in PATH
  3. Project-local (if any)
- Report clearly if missing

### A3. SourceCompilerImpl.compileKotlin
- Build kotlinc command:
  kotlinc -d <outDir> -cp <classpath> -jvm-target 17 <sources>
- Add Kotlin stdlib + android runtime to classpath
- Handle compilation errors → CompilerException

### A4. Pipeline Integration
- IncrementalBuildPipeline detects .kt files
- Routes to compileKotlin instead of compileJava
- Mixed projects: compile both, merge classpaths

### A5. Test: TEST-KOTLIN
- Minimal Kotlin Android app
- One Activity, no Compose
- Verify .class generation → DEX → APK

---

## Phase B: Jetpack Compose Compiler Plugin

### B1. Compose Plugin Detection
- Compose compiler plugin ships with Kotlin 2.x
- Located at: ~/.hbe/kotlin/<version>/lib/compose-compiler-*.jar
- Or: maven coordinates org.jetbrains.kotlin:kotlin-compose-compiler-plugin:<version>

### B2. Compose Compilation Args
kotlinc
  -Xplugin=<compose-compiler-plugin>
  -P plugin:androidx.compose.compiler.plugins.kotlin:destinationDir=<genDir>
  -d <outDir>
  -cp <classpath>
  <sources>

### B3. Compose Dependencies
- Compose runtime, ui, material, foundation, animation
- Resolved from version catalog / build.gradle.kts
- Added to compilation classpath

### B4. Generated Sources
- Compose compiler generates code
- Include generated sources in DEX path

### B5. Test: TEST-COMPOSE
- Minimal Kotlin + Compose app
- @Composable function + setContent
- Verify compilation → APK

---

## Phase C: Android SDK Management

### C1. SDK Detection
- Scan ~/.hbe/sdk/platforms/
- Detect available API levels
- Match against project compileSdk

### C2. Missing SDK Handling
- Clear error: "compileSdk 36 required, only 34 installed"
- Provide download command or auto-download if possible
- Never silently substitute

### C3. Install android-36
- Download platform-36 from Google
- Extract to ~/.hbe/sdk/platforms/android-36/

---

## Phase D: Version Catalog Parsing

### D1. TOML Parser
Parse gradle/libs.versions.toml:
[versions]
kotlin = "2.2.10"
composeBom = "2025.06.00"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }

[plugins]
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }

### D2. Resolution
- version.ref → lookup in [versions]
- group + name → Maven coordinate
- platform(...) → BOM (handle separately)
- Map aliases → Maven coordinates for DependencyManager

### D3. Plugin Aliases
- alias(libs.plugins.x) → resolve plugin ID + version
- apply to build configuration

---

## Phase E: Gradle Kotlin DSL

### E1. Improved .kts Parser
Handle in ProjectImporter:
- plugins { alias(libs.plugins.x) apply false }
- alias(libs.plugins.x)
- buildFeatures { compose = true }
- isMinifyEnabled = true
- signingConfigs { create("release") { ... } }
- compileSdk = 36 (integer, not string)
- namespace = "dev.krinry.jarvis"

### E2. Compose Configuration Block
detect buildFeatures { compose = true }
→ set useCompose = true in pipeline

---

## Phase F: Release Signing

### F1. Signing Config Parsing
- Parse signingConfigs { create("release") { storeFile, ... } }
- Support keystore file + passwords
- Support env var fallback

### F2. Wire to SignerImpl
- Debug: auto-generated keystore (existing)
- Release: project-supplied keystore

---

## Phase G: Integration Tests

Independent test projects:
- TEST-JAVA: minimal Java app
- TEST-KOTLIN: minimal Kotlin app
- TEST-MIXED: Java + Kotlin
- TEST-XML: Java/Kotlin + XML UI
- TEST-COMPOSE: Kotlin + Compose
- TEST-COMPOSE-MIXED: Compose + resources + deps
- TEST-MULTI-MODULE: multi-module project

Each must pass before testing HMx-assistant-main.

---

## Java Preservation Rule

After EVERY phase:
1. Run existing Java tests (TEST-01..07)
2. Verify Java pipeline unchanged
3. Only then proceed to next phase

Java support is NEVER removed or downgraded.

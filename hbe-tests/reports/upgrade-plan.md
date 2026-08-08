# HMX Capability Upgrade Plan — HMx-assistant-main Compatibility

**Date:** 2026-08-08
**Target:** Build HMx-assistant-main (Kotlin + Compose, compileSdk 36, version catalog, .kts)

---

## Current State

HMX can build Java-based Android projects with AAR dependencies and XML resources.
It CANNOT build Kotlin or Compose projects.

**Verification:** 7/7 Java test projects (TEST-01..07) build successfully.

---

## Gap Analysis

### Gap 1: Kotlin Compiler (CRITICAL)
- **Project needs:** Kotlin 2.2.10 compilation (41 source files)
- **HMX has:** `compileKotlin()` method that calls `kotlinc`
- **Reality:** `kotlinc` is NOT installed on this system
- **Fix:** Install Kotlin compiler OR find a way to invoke it

### Gap 2: Jetpack Compose Compiler Plugin (CRITICAL)
- **Project needs:** `org.jetbrains.kotlin.plugin.compose` to compile @Composable functions
- **HMX has:** No Compose support whatsoever
- **Fix:** Integrate Compose compiler plugin into the Kotlin compilation pipeline

### Gap 3: compileSdk 36 (CRITICAL)
- **Project needs:** android-36 platform
- **HMX has:** Only android-34 installed
- **Fix:** Download and install android-36 platform

### Gap 4: Version Catalog (CRITICAL)
- **Project needs:** Parse `gradle/libs.versions.toml` for all dependencies
- **HMX has:** No version catalog parsing — only reads dependencies from build.gradle
- **Fix:** Implement TOML parser for libs.versions.toml

### Gap 5: Gradle Kotlin DSL (HIGH)
- **Project needs:** Parse `.gradle.kts` files (plugins, alias(), buildFeatures, etc.)
- **HMX has:** Partial regex support for namespace/minSdk; does NOT handle `alias()`, `libs.*`, `plugins {}`, `buildFeatures {}`, `compose {}`
- **Fix:** Improve .kts parser for common Android constructs

### Gap 6: Release Signing (MEDIUM)
- **Project needs:** Keystore-based release signing
- **HMX has:** Debug keystore only
- **Fix:** Implement signing config parsing + keystore support

---

## Implementation Plan

### Phase A: Kotlin Toolchain
1. Install Kotlin compiler (download from JetBrains or package manager)
2. Update SourceCompilerImpl.compileKotlin to properly invoke kotlinc
3. Add Kotlin stdlib + android runtime to classpath
4. Test with a minimal Kotlin project

### Phase B: Compose Compiler Plugin
1. Locate/download Compose compiler plugin (Kotlin 2.2.10 matches compose plugin)
2. Add compose compiler args to kotlinc invocation:
   - `-Xplugin=<compose-compiler-plugin>`
   - `-P plugin:androidx.compose.compiler.plugins.kotlin:destinationDir=<gen>`
3. Handle generated Compose code
4. Test with a minimal Compose project

### Phase C: SDK Management
1. Download android-36 platform
2. Update SdkManager to detect and use it
3. Report clearly when SDK is missing

### Phase D: Version Catalog
1. Implement libs.versions.toml parser
2. Resolve `[versions]`, `[libraries]`, `[plugins]` sections
3. Handle `version.ref`, `group`+`name` notation
4. Map aliases to Maven coordinates
5. Feed resolved dependencies into existing Maven resolver

### Phase E: Gradle Kotlin DSL
1. Improve ProjectImporter to handle .kts:
   - `plugins { alias(libs.plugins.x) apply false }`
   - `alias(libs.plugins.x)`
   - `buildFeatures { compose = true }`
   - `isMinifyEnabled = true`
   - `signingConfigs { create("release") { ... } }`
2. Handle string interpolation and Kotlin DSL function calls

### Phase F: Release Signing
1. Parse signingConfigs from build.gradle.kts
2. Support keystore file + passwords
3. Wire into SignerImpl

### Phase G: Integration Test
1. Build HMx-assistant-main with upgraded HMX
2. Compare with reference Gradle build
3. Fix issues iteratively
4. Report results

---

## Estimated Effort

| Phase | Complexity | Est. Lines |
|-------|-----------|------------|
| A: Kotlin | High (install + integrate) | ~200 |
| B: Compose | Very High (compiler plugin) | ~300 |
| C: SDK | Medium (download + detect) | ~100 |
| D: Version Catalog | Medium (TOML parser) | ~250 |
| E: Kotlin DSL | High (parser improvements) | ~300 |
| F: Signing | Medium | ~100 |
| G: Testing | High (debugging) | ~200 |
| **Total** | | **~1450** |

This is a **multi-day effort** requiring significant new capability.

---

## Decision Point

Before proceeding, confirm:

1. Should I implement ALL phases (full Kotlin + Compose support)?
2. Or should I start with just Phase A (Kotlin only, no Compose) and test with a minimal Kotlin project first?
3. Or should I focus on making HMX build a SIMPLER Kotlin project first (no Compose), then add Compose later?

The safest incremental path:
- **Phase A** → test with minimal Kotlin project
- **Phase D** → test version catalog parsing
- **Phase C** → add android-36
- **Phase B** → add Compose
- **Phases E+F** → improve .kts and signing
- **Phase G** → full HMx-assistant-main test

**Risk:** Even with all phases, Compose compilation is complex and may require specific plugin versions matching Kotlin 2.2.10.

# AndroidIDE Build Architecture — Migration Analysis for HMX

**Date:** 2026-08-08
**Purpose:** Determine which AndroidIDE components can modernize HMX.

---

## KEY FINDING

**AndroidIDE does NOT have its own build engine.** It uses standard **Gradle + Android Gradle Plugin (AGP)**. The actual Android build is performed by Gradle, not by AndroidIDE-specific code.

AndroidIDE's build-related contributions are:
1. ARM64-native AAPT2/aidl/zipalign binaries
2. SDK installer script (idesetup)
3. Gradle init script plugin (configures repos/classpath)
4. AAPT2 Maven override mechanism

This means HMX **cannot reuse AndroidIDE's build logic** because there is none — AndroidIDE delegates to Gradle. HMX must continue building its own pipeline.

---

## ANDROIDIDE BUILD ARCHITECTURE

### How AndroidIDE Builds Projects

```
User Project
    ↓
Gradle Wrapper (gradlew)
    ↓
AndroidIDE Init Script Plugin (auto-injected)
    ├── Configures repositories (google, mavenCentral)
    ├── Configures classpath
    └── Applies AndroidIDEPlugin to subprojects
        ↓
Android Gradle Plugin (AGP)
    ├── AAPT2 (resource compilation)
    ├── javac/kotlinc (source compilation)
    ├── d8 (DEX generation)
    └── apksigner (signing)
```

AndroidIDE itself is just an IDE that **runs Gradle**. The build engine is Gradle + AGP.

### AndroidIDE's Actual Build Components

| Component | What it does | Reusable? |
|-----------|--------------|-----------|
| `androidide-tools/idesetup` | Downloads JDK + SDK | Pattern only (GPL-3.0) |
| ARM64 AAPT2 binaries | Native binary builds | **YES** — direct use |
| `android.aapt2FromMavenOverride` | Forces ARM64 aapt2 | **YES** — adopt pattern |
| Gradle init script plugin | Configures Gradle repos | Concept only |
| LogSender plugin | Debug logging dep | Not relevant |

---

## COMPONENT ANALYSIS

### 1. androidide-tools (SDK Installer)

| Field | Value |
|-------|-------|
| Repository | AndroidIDEOfficial/androidide-tools |
| Path | scripts/idesetup |
| Purpose | Downloads JDK 17/21 + Android SDK |
| License | **GPL-3.0** (copyleft — cannot copy code) |
| Status | Archived Dec 2024 |
| Recommendation | **DO NOT USE** (GPL license, archived) |

**Why not:** GPL-3.0 is copyleft. HMX would have to release under GPL-3.0 too. The repo is archived. Use the *concept* of a lightweight installer instead.

### 2. ARM64 Build Tools

| Field | Value |
|-------|-------|
| Repository | Commit451/android-arm-build-tools |
| Purpose | Drop-in ARM64 native binaries |
| License | Apache 2.0 (AOSP-based) |
| Provides | aapt2, aidl, zipalign, split-select |
| Size | ~3.4 MB per version |
| Recommendation | **USE DIRECTLY** |

**Why:** Apache 2.0, actively maintained, solves the ARM64 native binary problem. HMX already has working ARM64 aapt2 via apt, but this provides a clean fallback.

### 3. AAPT2 Maven Override

| Field | Value |
|-------|-------|
| Mechanism | `android.aapt2FromMavenOverride` property |
| Purpose | Forces AGP 9.x+ to use ARM64 aapt2 instead of x86_64 from Maven |
| Recommendation | **ADOPT** |

**Why:** AGP 9.x+ pulls its own aapt2 from Maven (x86_64-only). HMX must override this to use the ARM64 binary. This is a configuration pattern, not code.

### 4. Kotlin Compiler

| Field | Value |
|-------|-------|
| Source | JetBrains GitHub releases |
| License | Apache 2.0 |
| Size | ~75 MB per version |
| Provides | kotlinc + Compose compiler plugin |
| Recommendation | **USE DIRECTLY** |

**Why:** Apache 2.0, official distribution, includes Compose plugin. HMX can download and invoke kotlinc as an external tool (same pattern as javac/d8).

### 5. Android SDK Platforms

| Field | Value |
|-------|-------|
| Source | Google sdkmanager |
| License | Google TOS |
| Recommendation | **DOWNLOAD** (android-36 needed) |

**Why:** HMx-assistant-main needs compileSdk 36. HMX must support it.

---

## HMX CURRENT ARCHITECTURE

### What HMX Has

| Component | Implementation | Status |
|-----------|----------------|--------|
| CLI | HbeCli.kt | Working |
| Project Import | ProjectImporter.kt | Working (Groovy + partial .kts) |
| Dependency Resolution | DependencyManagerImpl (Maven) | Working |
| AAR Extraction | extractAar() | Working |
| Manifest Merger | ManifestMerger.kt | Working (new) |
| Resource Compiler | ResourceCompilerImpl (aapt2) | Working |
| Source Compiler | SourceCompilerImpl (javac) | Java working, Kotlin untested |
| DEX Engine | DexEngineImpl (d8) | Working |
| Packager | PackagerImpl | Working |
| Signer | SignerImpl | Debug only |
| Pipeline | IncrementalBuildPipeline | Working |

### What HMX is Missing

| Capability | Gap | Priority |
|------------|-----|----------|
| Kotlin compilation | kotlinc not installed, untested | CRITICAL |
| Compose compiler | No plugin support | CRITICAL |
| android-36 | Only android-34 installed | CRITICAL |
| Version catalog | libs.versions.toml not parsed | CRITICAL |
| Kotlin DSL | .kts partially supported | HIGH |
| Release signing | Keystore not implemented | MEDIUM |
| AIDL | Not invoked | LOW |

---

## COMPONENT COMPARISON

| HMX Component | AndroidIDE Equivalent | Recommendation |
|---------------|----------------------|----------------|
| SourceCompilerImpl | Gradle + kotlinc | Keep custom, add kotlinc invocation |
| DependencyManagerImpl | Gradle dependency resolution | Keep custom (Maven-based) |
| ManifestMerger | AGP manifest merger | Keep custom (already implemented) |
| ResourceCompilerImpl | AAPT2 (same tool) | Keep custom (already using aapt2) |
| DexEngineImpl | D8 (same tool) | Keep custom (already using d8) |
| SignerImpl | apksigner (same tool) | Keep custom (already using apksigner) |
| ProjectImporter | N/A (Gradle reads build files) | Keep custom |

**Conclusion:** HMX's architecture is sound. AndroidIDE has no build-engine code to reuse. HMX should:
1. Add kotlinc as an external tool (like javac/d8)
2. Add Compose compiler plugin args
3. Download android-36
4. Implement version catalog parser
5. Improve .kts parsing

---

## REUSABLE COMPONENTS

### Direct Use
1. **Commit451/android-arm-build-tools** — ARM64 native binaries
   - License: Apache 2.0
   - Size: ~3.4 MB
   - Use: Drop-in replacement if apt aapt2 fails

2. **Kotlin Compiler (JetBrains)** — kotlinc + Compose plugin
   - License: Apache 2.0
   - Size: ~75 MB
   - Use: Download, extract, invoke as external tool

### Adopt Pattern
1. **AAPT2 Maven Override** — `android.aapt2FromMavenOverride`
   - Use: Ensure ARM64 aapt2 is always used

### Do Not Use
1. **androidide-tools** — GPL-3.0, archived
2. **AndroidIDE Gradle Plugin** — IDE-specific, Gradle-coupled
3. **LogSender** — IDE debugging, not build

---

## MISSING HMX CAPABILITIES

| Capability | Required For | Effort |
|------------|--------------|--------|
| Kotlin compilation | HMx-assistant-main (100% Kotlin) | Medium |
| Compose compiler | HMx-assistant-main (Compose UI) | Medium |
| android-36 platform | compileSdk 36 | Low (download) |
| Version catalog | libs.versions.toml | Medium |
| Kotlin DSL parsing | .gradle.kts files | Medium |
| Release signing | Keystore-based signing | Low |
| AIDL | Rarely needed | LOW |

---

## REQUIRED DOWNLOADS

| Component | Source | Size | License |
|-----------|--------|------|---------|
| kotlin-compiler-2.2.10.zip | JetBrains GitHub | ~75 MB | Apache 2.0 |
| android-36 platform | Google sdkmanager | ~50 MB | Google TOS |
| build-tools 36.0.0 ARM64 | Commit451 or apt | ~3.4 MB | Apache 2.0 |
| platform-tools | Google sdkmanager | ~15 MB | Google TOS |

**Total: ~150 MB one-time**

---

## APPROXIMATE REQUIREMENTS

### Storage
- Current HMX: ~50 MB (engine + SDK)
- After upgrade: ~200 MB (engine + SDK + Kotlin + android-36)
- Per-project build: ~50-200 MB (dependencies + intermediates)

### RAM
- Current: ~64-128 MB peak (Java projects)
- After Kotlin: ~128-256 MB peak (kotlinc is heavier)
- Mitigation: streaming, bounded buffers, temp files

---

## LICENSE CONSIDERATIONS

| Component | License | Compatible with HMX? |
|-----------|---------|---------------------|
| Kotlin compiler | Apache 2.0 | Yes |
| Android SDK | Google TOS | Yes |
| AOSP build-tools | Apache 2.0 | Yes |
| Commit451/arm-tools | Apache 2.0 | Yes |
| androidide-tools | GPL-3.0 | **NO** (copyleft) |
| AndroidIDE source | Apache 2.0 | Yes (but irrelevant — IDE code) |

---

## RISKS

| Risk | Impact | Mitigation |
|------|--------|------------|
| kotlinc not available for ARM64 | Can't compile Kotlin | Use JVM-based kotlinc (runs on any JVM) |
| Compose compiler version mismatch | Compilation fails | Match Kotlin + Compose versions exactly |
| android-36 download blocked | Can't build project | Fallback to android-34 with clear warning |
| kotlinc memory pressure | OOM on low-end device | `-J-Xmx` limits, streaming |
| .kts parsing edge cases | Misread config | Extensive test coverage |
| AGP 9.x aapt2 override | Wrong aapt2 binary | Always set override to ARM64 binary |

---

## RECOMMENDED ARCHITECTURE

HMX should remain a **custom lightweight build engine**. AndroidIDE has no build-engine code to reuse.

### Target Structure
```
HMX Build Engine
├── Project Layer
│   ├── ProjectImporter (Groovy + Kotlin DSL)
│   ├── GradleMetadata (namespace/minSdk scan)
│   └── VersionCatalogParser (libs.versions.toml) [NEW]
│
├── Dependency Layer
│   ├── DependencyManagerImpl (Maven resolver)
│   └── AAR extractor + manifest collector
│
├── Toolchain Layer
│   ├── JDK (javac)
│   ├── Kotlin (kotlinc) [NEW]
│   ├── Compose compiler plugin [NEW]
│   ├── Android SDK (platforms/build-tools)
│   ├── AAPT2 (ARM64 native)
│   ├── AIDL (if needed)
│   └── D8 (DEX)
│
├── Build Layer
│   ├── ManifestMerger
│   ├── ResourceCompiler (aapt2 compile/link)
│   ├── ResourceMerger (values dedup)
│   ├── SourceCompiler (javac + kotlinc)
│   └── DEX Engine
│
├── Output Layer
│   ├── Packager (APK zip)
│   ├── Aligner (zipalign)
│   ├── Signer (debug + release)
│   └── Verifier
│
└── Pipeline
    ├── IncrementalBuildPipeline
    ├── BuildProgressTracker
    └── CacheManager
```

---

## MIGRATION STRATEGY

### Phase 1: Research Complete
- Document what AndroidIDE provides (spoiler: nothing reusable for build engine)
- Identify actual reusable components (ARM64 binaries, Kotlin compiler)
- Create this analysis

### Phase 2: Integration Plan
- Download Kotlin compiler
- Add kotlinc invocation to SourceCompilerImpl
- Add Compose compiler plugin args
- Download android-36
- Implement version catalog parser
- Improve .kts parsing

### Phase 3: Implementation Order
1. Kotlin toolchain (download + invoke)
2. Compose compiler plugin
3. android-36 platform
4. Version catalog parser
5. Kotlin DSL improvements
6. Release signing
7. Full HMx-assistant-main test

---

## CONCLUSION

**AndroidIDE does not provide a build engine.** It uses Gradle. HMX must continue building its own pipeline.

The only reusable components are:
1. **ARM64 native binaries** (Commit451) — direct use
2. **Kotlin compiler** (JetBrains) — direct use as external tool
3. **AAPT2 override pattern** — adopt configuration

Everything else (dependency resolution, manifest merging, resource compilation, packaging) must remain HMX's custom implementation. The architecture is sound; it just needs Kotlin/Compose toolchain additions.

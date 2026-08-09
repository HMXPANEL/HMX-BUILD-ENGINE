# AndroidIDE Build Architecture — Migration Analysis for HMX

**Date:** 2026-08-08
**Status:** Research audit only — no code changes

---

## KEY FINDING

**AndroidIDE does NOT have its own build engine.** It uses standard **Gradle + Android Gradle Plugin (AGP)**. The actual Android build is performed by Gradle, not by AndroidIDE-specific code.

AndroidIDE is an IDE that wraps Gradle. Its build-related contributions are limited to:
1. ARM64-native AAPT2/aidl/zipalign binaries
2. SDK installer script (idesetup)
3. Gradle init script plugin (configures repos/classpath)
4. AAPT2 Maven override mechanism

**Implication:** HMX cannot reuse AndroidIDE's build logic because there is none. HMX must continue building its own pipeline, but can reuse specific toolchain components.

---

## CURRENT HMX STATUS

### Working
- Java compilation (javac in-process + fallback)
- XML resources (aapt2 compile/link)
- AAR extraction (classes.jar, res, manifest, assets)
- AAR manifest merging (namespace-aware, placeholder subst)
- Maven dependency resolution (transitive)
- DEX generation (d8)
- APK packaging + debug signing
- 7/7 Java test projects build

### Missing
| Capability | Priority |
|------------|----------|
| Kotlin compilation | CRITICAL |
| Compose compiler plugin | CRITICAL |
| android-36 platform | CRITICAL |
| Version catalog parsing | CRITICAL |
| Gradle Kotlin DSL (.kts) | HIGH |
| Release signing | MEDIUM |
| AIDL | LOW |

### Architecture
```
Project → Scan → Dependencies → Manifest Merge → Resource Merge
→ Java Compile → DEX → Package → Sign → Verify
```

---

## ANDROIDIDE BUILD ARCHITECTURE

### How AndroidIDE Builds

```
User Project
    ↓
Gradle Wrapper (gradlew)
    ↓
AndroidIDE Init Script Plugin (auto-injected)
    ├── Configures repositories
    ├── Configures classpath
    └── Applies AndroidIDEPlugin to subprojects
        ↓
Android Gradle Plugin (AGP) — does the actual build
    ├── AAPT2 (resources)
    ├── javac/kotlinc (sources)
    ├── d8 (DEX)
    └── apksigner (signing)
```

### AndroidIDE's Build Components

| Component | Repository | Purpose | Reusable? |
|-----------|------------|---------|-----------|
| `idesetup` script | androidide-tools | Downloads JDK + SDK | Pattern only (GPL-3.0) |
| ARM64 AAPT2 builds | platform-tools | Native binary builds | **YES** |
| AAPT2 Maven override | AndroidIDE docs | Forces ARM64 aapt2 | **YES** |
| Gradle init script | gradle-plugin module | Configures Gradle | Concept only |
| LogSender | gradle-plugin module | Debug logging | Not relevant |

---

## ANDROIDIDE COMPONENT MAP

### 1. androidide-tools (SDK Installer)

| Field | Value |
|-------|-------|
| Repository | AndroidIDEOfficial/androidide-tools |
| Path | scripts/idesetup |
| Purpose | Downloads JDK 17/21 + Android SDK |
| License | **GPL-3.0** (copyleft) |
| Status | Archived Dec 2024 |
| Verdict | **DO NOT USE** |

**Why not:** GPL-3.0 is copyleft. HMX would have to release under GPL-3.0. Archived. Use the *concept* of a lightweight installer instead.

### 2. platform-tools (ARM64 Builds)

| Field | Value |
|-------|-------|
| Repository | AndroidIDEOfficial/platform-tools |
| Purpose | Builds aapt2, aidl, adb from AOSP source |
| License | Apache 2.0 |
| Verdict | **REFERENCE** |

**Why:** Provides build scripts for compiling native tools from AOSP. Useful reference but HMX can use pre-built binaries.

### 3. android-arm-build-tools (Commit451)

| Field | Value |
|-------|-------|
| Repository | Commit451/android-arm-build-tools |
| Purpose | Drop-in ARM64 native binaries |
| License | Apache 2.0 |
| Provides | aapt2, aidl, zipalign, split-select |
| Size | ~3.4 MB per version |
| Verdict | **USE DIRECTLY** |

**Why:** Apache 2.0, actively maintained, solves ARM64 native binary problem. Provides versions 35.0.1 through 37.0.0.

### 4. AAPT2 Maven Override

| Field | Value |
|-------|-------|
| Mechanism | `android.aapt2FromMavenOverride` property |
| Purpose | Forces AGP 9.x+ to use ARM64 aapt2 |
| Verdict | **ADOPT** |

### 5. Kotlin Compiler

| Field | Value |
|-------|-------|
| Source | JetBrains GitHub releases |
| License | Apache 2.0 |
| Size | ~75 MB per version |
| Verdict | **USE DIRECTLY** |

---

## MISSING HMX CAPABILITIES

| Capability | Gap | Priority |
|------------|-----|----------|
| Kotlin compilation | kotlinc not installed | CRITICAL |
| Compose compiler | No plugin support | CRITICAL |
| android-36 | Only android-34 installed | CRITICAL |
| Version catalog | libs.versions.toml not parsed | CRITICAL |
| Kotlin DSL | .kts partially supported | HIGH |
| Release signing | Keystore not implemented | MEDIUM |
| AIDL | Not invoked | LOW |

---

## REUSABLE COMPONENTS

### Direct Use
1. **Commit451/android-arm-build-tools** — ARM64 native binaries (Apache 2.0, ~3.4 MB)
2. **Kotlin Compiler (JetBrains)** — kotlinc + Compose plugin (Apache 2.0, ~75 MB)
3. **AAPT2 override pattern** — `android.aapt2FromMavenOverride`

### Adopt Pattern
1. **SDK installer concept** — lightweight download + verify + extract
2. **ARM64 binary verification** — check architecture before use

### Do Not Use
1. **androidide-tools** — GPL-3.0, archived
2. **AndroidIDE Gradle Plugin** — IDE-specific, Gradle-coupled
3. **LogSender** — IDE debugging, not build
4. **terminal-packages** — terminal emulation, irrelevant
5. **aaptcompiler** — IDE XML analysis, not build

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

| Component | License | Compatible? |
|-----------|---------|-------------|
| Kotlin compiler | Apache 2.0 | Yes |
| Android SDK | Google TOS | Yes |
| AOSP build-tools | Apache 2.0 | Yes |
| Commit451/arm-tools | Apache 2.0 | Yes |
| androidide-tools | GPL-3.0 | **NO** |
| AndroidIDE source | Apache 2.0 | Yes (but irrelevant) |

---

## RISKS

| Risk | Impact | Mitigation |
|------|--------|------------|
| kotlinc ARM64 availability | Can't compile Kotlin | Use JVM-based kotlinc (runs on any JVM) |
| Compose version mismatch | Compilation fails | Match Kotlin + Compose versions |
| android-36 download blocked | Can't build | Fallback to android-34 with warning |
| kotlinc memory pressure | OOM on low-end | `-J-Xmx` limits, streaming |
| .kts parsing edge cases | Misread config | Extensive test coverage |
| AGP 9.x aapt2 override | Wrong binary | Always set override to ARM64 |

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

### Phase 1: Research Complete ✓
- Document what AndroidIDE provides (nothing reusable for build engine)
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

Everything else must remain HMX's custom implementation.

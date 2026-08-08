# HMX Build Engine — AndroidIDE-Based Migration Plan

**Date:** 2026-08-08
**Goal:** Modernize HMX using AndroidIDE's build toolchain approach while keeping it lightweight.

---

## CURRENT HMX STATUS

### What Works
- Java compilation (javac in-process + fallback)
- XML resource compilation (aapt2 compile/link)
- AAR extraction (classes.jar, res, manifest, assets)
- AAR manifest merging (namespace-aware, placeholder substitution)
- Maven dependency resolution (transitive)
- DEX generation (d8)
- APK packaging + debug signing
- 7/7 Java test projects build successfully

### What is Missing
- **Kotlin compilation** — kotlinc not installed, no Compose compiler plugin
- **compileSdk 36** — only android-34 installed
- **Version catalog** — libs.versions.toml not parsed
- **Gradle Kotlin DSL** — .kts partially supported
- **Release signing** — keystore handling not implemented
- **android-36 platform** — not installed

### Architecture
```
Project → Scan → Dependencies → Manifest Merge → Resource Merge
→ Java/Kotlin Compile → DEX → Package → Sign → Verify
```

---

## ANDROIDIDE BUILD COMPONENTS

AndroidIDE splits build tooling into specialized repositories:

### 1. androidide-tools (`AndroidIDEOfficial/androidide-tools`)
- **Purpose:** SDK installer script (`idesetup`)
- **Installs:** JDK 17/21, Android SDK, command-line tools
- **Relevance:** HMX can reuse the *concept* of a lightweight installer
- **Reusable:** Install script pattern, manifest-based SDK downloads

### 2. platform-tools (`AndroidIDEOfficial/platform-tools`)
- **Purpose:** Builds Android build/platform tools from AOSP source
- **Builds:** aapt2, aidl, adl, etc. for ARM64
- **Relevance:** Critical for ARM64 support
- **Reusable:** Build scripts, AOSP source references

### 3. android-arm-build-tools (`Commit451/android-arm-build-tools`)
- **Purpose:** Drop-in ARM64 native binaries for build-tools
- **Provides:** aapt2, aidl, zipalign, split-select for linux-arm64
- **Why needed:** Google's sdkmanager only ships x86_64 native binaries
- **Versions:** 35.0.1, 36.0.0, 36.1.0, 37.0.0
- **License:** AOSP-based (Apache 2.0)
- **Reusable:** Direct download and use

### 4. terminal-packages
- **Purpose:** Fork of termux-packages for terminal tools
- **Relevance:** Low for HMX (terminal emulation, not build)

### 5. aaptcompiler
- **Purpose:** XML parsing/analysis for Android modules
- **Relevance:** Low (IDE feature, not build pipeline)

---

## MISSING CAPABILITIES

| Capability | Current HMX | Required | Source |
|------------|-------------|----------|--------|
| Kotlin compiler | Missing | kotlinc 2.2.10 | JetBrains GitHub releases |
| Compose compiler | Missing | Bundled with Kotlin 2.x | JetBrains |
| android-36 platform | Missing | platform-36 | Google sdkmanager |
| Version catalog | Missing | TOML parser | Implement natively |
| Gradle Kotlin DSL | Partial | .kts parser | Extend ProjectImporter |
| Release signing | Missing | Keystore support | SignerImpl extension |
| ARM64 aapt2 | Working (apt) | Verify native binary | Already have via apt |
| AIDL compiler | Missing | aidl binary | AOSP platform-tools |

---

## REUSABLE COMPONENTS

### Direct Reuse (Download & Integrate)
1. **Commit451/android-arm-build-tools** — ARM64 aapt2/aidl/zipalign/split-select
   - Drop-in replacement for x86_64 binaries
   - Apache 2.0 licensed (AOSP)
   - ~3.4 MB per version

2. **Kotlin Compiler** — `kotlin-compiler-<version>.zip` from JetBrains
   - Includes kotlinc + Compose compiler plugin
   - Extract to `~/.hbe/kotlin/`
   - ~75 MB per version

### Conceptual Reuse (Adapt Pattern)
1. **idesetup script pattern** — HMX can have its own SDK installer
2. **AOSP build scripts** — Reference for building tools from source
3. **AndroidIDE's modular approach** — Keep components separate

### Do NOT Use
1. **AndroidIDE IDE features** — code completion, syntax highlighting (not build)
2. **terminal-packages** — terminal emulation irrelevant to build pipeline
3. **Full AndroidIDE source** — too heavyweight, IDE-focused

---

## REQUIRED DOWNLOADS

| Component | Source | Size | License |
|-----------|--------|------|---------|
| kotlin-compiler-2.2.10.zip | JetBrains GitHub | ~75 MB | Apache 2.0 |
| android-36 platform | Google sdkmanager | ~50 MB | Google TOS |
| build-tools 36.0.0 (ARM64) | Commit451 or apt | ~3.4 MB | Apache 2.0 |
| platform-tools (latest) | Google sdkmanager | ~15 MB | Google TOS |

---

## LIGHTWEIGHT ARCHITECTURE

### Target Design
```
~/.hbe/
├── kotlin/
│   └── kotlinc-2.2.10/          # Kotlin compiler + Compose plugin
│       ├── bin/kotlinc
│       └── lib/compose-compiler-*.jar
├── sdk/
│   ├── build-tools/
│   │   ├── 29.0.3/              # Existing
│   │   └── 36.0.0/              # New (ARM64 binaries)
│   └── platforms/
│       ├── android-34/          # Existing
│       └── android-36/          # New
├── dependencies/                # Maven cache
└── cache/                       # Build cache

Total added: ~150 MB (one-time)
```

### Compilation Paths
```
Java *.java  ──→ javac ──────────────→ .class ─┐
                                               ├──→ DEX → APK
Kotlin *.kt ──→ kotlinc (+compose) ──→ .class ─┘
```

### Memory-Conscious Design
- Stream large files (don't load entire APKs into RAM)
- Bounded dependency graph (process in chunks)
- Disk-based build cache (not in-memory)
- Temporary files cleaned after each stage
- Incremental compilation (only changed sources)

---

## MIGRATION PLAN

### Phase A: Kotlin Toolchain (IMMEDIATE)
1. Download kotlin-compiler-2.2.10.zip
2. Extract to ~/.hbe/kotlin/kotlinc-2.2.10/
3. Update ToolRunnerImpl to find kotlinc in ~/.hbe/kotlin/
4. Add Kotlin stdlib to classpath in compileKotlin
5. Test with minimal Kotlin project

### Phase B: Compose Compiler Plugin
1. Locate compose-compiler plugin JAR in kotlinc lib/
2. Add `-Xplugin` and `-P plugin:...` args to kotlinc
3. Handle generated Compose sources
4. Test with minimal Compose project

### Phase C: Android SDK 36
1. Download platform-36 via sdkmanager or direct
2. Install to ~/.hbe/sdk/platforms/android-36/
3. Update SdkManager to detect it
4. Verify android.jar availability

### Phase D: Version Catalog Parser
1. Implement TOML parser for libs.versions.toml
2. Resolve [versions], [libraries], [plugins]
3. Map aliases → Maven coordinates
4. Integrate with DependencyManager

### Phase E: Gradle Kotlin DSL
1. Extend ProjectImporter for .kts constructs
2. Handle alias(), plugins {}, buildFeatures {}
3. Handle compose {} configuration block
4. Parse signingConfigs

### Phase F: ARM64 Build Tools
1. Verify current aapt2/aidl/zipalign are ARM64-native
2. If not, download from Commit451/android-arm-build-tools
3. Replace x86_64 binaries with ARM64 equivalents

### Phase G: Release Signing
1. Parse signingConfigs from build files
2. Support keystore files + passwords
3. Wire into SignerImpl

---

## TEST PLAN

### Test Projects (Independent)
| Test | What it validates |
|------|-------------------|
| TEST-JAVA | Minimal Java Android app |
| TEST-KOTLIN | Minimal Kotlin Android app |
| TEST-MIXED | Java + Kotlin |
| TEST-XML | Java/Kotlin + XML UI |
| TEST-COMPOSE | Kotlin + Compose |
| TEST-COMPOSE-MIXED | Compose + resources + deps |
| TEST-MULTI-MODULE | Multi-module project |
| TEST-CATALOG | libs.versions.toml parsing |
| TEST-KOTLIN-DSL | .gradle.kts parsing |
| EXTERNAL | HMx-assistant-main (real-world) |

### Verification Per Test
```
SCAN              PASS/FAIL
DEPENDENCIES      PASS/FAIL
MANIFEST          PASS/FAIL
RESOURCES         PASS/FAIL
JAVA COMPILE      PASS/FAIL
KOTLIN COMPILE    PASS/FAIL
COMPOSE COMPILE   PASS/FAIL
DEX               PASS/FAIL
PACKAGE           PASS/FAIL
SIGN              PASS/FAIL
APK VERIFY        PASS/FAIL
INSTALL           PASS/FAIL / NOT TESTED
LAUNCH            PASS/FAIL / NOT TESTED
RUNTIME           PASS/FAIL / NOT TESTED
```

---

## ARM64 COMPATIBILITY

### Current Environment
- Platform: linux-aarch64 (Termux PRot)
- Java: OpenJDK 25 (ARM64 native)
- aapt2: Installed via apt (ARM64 native verified working)

### ARM64 Native Binaries Needed
| Tool | Source | Status |
|------|--------|--------|
| aapt2 | apt (2.19-debian) | ✓ Working |
| aidl | AOSP platform-tools | Need to verify |
| zipalign | apt | ✓ Working |
| d8 | build-tools (Java) | ✓ Works (JVM-based) |
| apksigner | build-tools (Java) | ✓ Works (JVM-based) |
| kotlinc | JetBrains (JVM-based) | ✓ Works (JVM-based) |

**Key insight:** Java-based tools (d8, apksigner, kotlinc) work on ARM64 via JVM. Only native binaries (aapt2, aidl, zipalign, split-select) need ARM64 builds.

### AGP 9.x Note
Android Gradle Plugin 9.x+ pulls its own aapt2 from Maven (x86_64-only). HMX must use `android.aapt2FromMavenOverride` or directly invoke the ARM64 aapt2 binary.

---

## LICENSING

| Component | License | Commercial Use |
|-----------|---------|----------------|
| Kotlin compiler | Apache 2.0 | ✓ |
| Android SDK | Google TOS | ✓ |
| AOSP build-tools | Apache 2.0 | ✓ |
| Commit451/arm-tools | Apache 2.0 | ✓ |
| AndroidIDE components | Apache 2.0 | ✓ |

---

## RISKS

| Risk | Impact | Mitigation |
|------|--------|------------|
| Compose compiler version mismatch | Compilation fails | Match Kotlin + Compose versions exactly |
| android-36 download fails | Can't build project | Fallback to android-34 with warning |
| kotlinc memory pressure | OOM on low-end device | Add `-J-Xmx` limits, streaming |
| .kts parsing edge cases | Misread config | Extensive test coverage |
| Native binary incompatibility | aapt2 crashes | Verify ARM64 before use |

---

## SUMMARY

HMX can be modernized by:
1. Downloading Kotlin compiler (75 MB) — immediate
2. Downloading android-36 platform (50 MB) — immediate
3. Implementing version catalog parser — medium effort
4. Improving Kotlin DSL parser — medium effort
5. Adding Compose compiler plugin args — medium effort
6. Verifying ARM64 native binaries — low effort

Total one-time download: ~125 MB
Code changes: ~1000 lines across 6 modules
No IDE bloat, no terminal emulation, no unnecessary components.

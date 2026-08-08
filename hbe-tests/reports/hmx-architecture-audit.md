# HMX Build Engine — Architecture Audit

**Date:** 2026-08-08

## Build Pipeline

```
Project
  → Project Scan (ProjectImporter: parse settings.gradle + build.gradle)
  → Dependency Resolution (DependencyManagerImpl: Maven resolver, AAR extraction)
  → Manifest Merge (ManifestMerger: app + AAR manifests, namespace-aware)
  → Resource Merge (ResourceCompilerImpl.mergeResources: values XML dedup)
  → Resource Compile (aapt2 compile --dir)
  → Resource Link (aapt2 link with merged manifest)
  → Java/Kotlin Compile (SourceCompilerImpl: javac in-process / kotlinc)
  → DEX (DexEngineImpl: d8)
  → APK Package (PackagerImpl: zip assembly)
  → APK Align (zipalign -f 4)
  → APK Sign (SignerImpl: apksigner + debug keystore)
```

## Component Map

| Component | File | Lines | Status |
|-----------|------|-------|--------|
| CLI | hbe-cli/HbeCli.kt | ~340 | Working |
| Project Importer | hbe-core/.../ProjectImporter.kt | ~320 | Working (Groovy + partial .kts) |
| Gradle Metadata | hbe-core/.../GradleMetadata.kt | ~75 | Working (namespace/minSdk scan) |
| Manifest Merger | hbe-core/.../ManifestMerger.kt | ~245 | Working (new) |
| Project Resolver | hbe-core/.../ProjectResolver.kt | ~160 | Working |
| Dependency Manager | hbe-dependency/DependencyManagerImpl.kt | ~450 | Working (Maven + AAR) |
| Resource Compiler | hbe-resources/ResourceCompilerImpl.kt | ~350 | Working |
| Source Compiler | hbe-compiler/SourceCompilerImpl.kt | ~230 | Java working, Kotlin untested |
| DEX Engine | hbe-dex/DexEngineImpl.kt | ~120 | Working |
| Packager | hbe-packager/PackagerImpl.kt | ~90 | Working |
| Signer | hbe-signer/SignerImpl.kt | ~80 | Working (debug only) |
| Pipeline (Incremental) | hbe-core/.../IncrementalBuildPipeline.kt | ~820 | Working |
| Pipeline (Default) | hbe-core/.../DefaultBuildPipeline.kt | ~340 | Working |
| Progress Tracker | hbe-core/.../BuildProgressTracker.kt | ~180 | Working |

## Current Capabilities

| Capability | Status | Notes |
|------------|--------|-------|
| Java compilation | ✓ | javac in-process |
| Kotlin compilation | ∂ | Method exists, untested |
| XML resources | ✓ | aapt2 compile/link |
| Values merge | ✓ | Dedup by name |
| AAR extraction | ✓ | classes.jar, res, manifest, assets |
| AAR manifest merge | ✓ | Namespace-aware, placeholder subst |
| Maven dependencies | ✓ | Transitive resolution |
| Debug APK signing | ✓ | Auto-generated debug keystore |
| Release signing | ✗ | Not implemented |
| Version catalog | ✗ | libs.versions.toml not parsed |
| compileSdk 34 | ✓ | Only android-34 installed |
| compileSdk 35/36 | ✗ | SDK not installed |
| Jetpack Compose | ✗ | No Compose compiler plugin |
| Kotlin DSL (.kts) | ∂ | Partial regex support |

## Known Dead Code (unreferenced by core pipeline)

- hbe-daemon/ — daemon mode stub
- hbe-recovery/ — returns null/fake
- hbe-diagnostics/ — buildAnalytics returns null
- hbe-plugins/ — speculative
- hbe-graph/ — unused (core defines own types)
- hbe-memory/ — 4 files for runtime probe
- hbe-scheduler/ — unused by main path
- DefaultHbeEngine — 7 stub methods, never wired
- PhaseExecutor placeholder — fake SUCCESS fallback

## Existing Tests

| Module | Test | Status |
|--------|------|--------|
| hbe-core | ManifestMergerTest (15) | PASS |
| hbe-core | IncrementalBuildPipelineTest (8) | PASS |
| hbe-core | IncrementalBuildPipelineTest fakes | Fixed for 8-task graph |
| hbe-resources | ResourceMergeTest (7) | PASS |
| hbe-resources | ResourceCompilerImplTest | SKIP (mockk fails in PRoot) |
| hbe-tests | 7 compatibility projects (TEST-01..07) | PASS |

## Critical Limitations

1. **No Kotlin compilation** — compileKotlin method exists but is untested and likely broken
2. **No Compose support** — Compose compiler plugin required for any Compose project
3. **No android-36** — HMX only supports up to android-34
4. **No version catalog** — libs.versions.toml not parsed
5. **No release signing** — debug keystore only

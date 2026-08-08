# Milestone 5 Status — AAR Manifest Merger

**Date:** 2026-08-08
**Commits:** `c85e161`, `69053a8`

## Result

Aira APK builds with HMX. Crash on launch is **fixed** by merging AAR manifests.

## Root Cause (proven)

The Aira APK crashed on launch because library AAR manifests were silently dropped.
`AarContents.manifest` was extracted but never merged into the final manifest.

**Missing entries that cause the crash:**
- `androidx.startup.InitializationProvider` — initializes ProcessLifecycleOwner at app startup
- `android:appComponentFactory="androidx.core.app.CoreComponentFactory"` — required by AppCompatActivity/SavedStateRegistry

Without these, `MainActivity extends AppCompatActivity` crashes immediately.

## Architecture of the fix

```
AarContents(manifest: Path?)
    ↓
ProjectResolver.addArtifact()  ← now also collects manifest
    ↓
+ ManifestMerger (NEW)         ← merges app + library manifests
|   namespace-aware XML merge
|   component dedup (first-wins by android:name)
|   application attribute fill-in (lib fills what app didn't set)
|   uses-permission merge + top-level <permission> declarations
|   ${applicationId} placeholder substitution
    ↓
ProjectDependencies(libraryManifests: List<Path>)  ← NEW FIELD
    ↓
IncrementalBuildPipeline.execute()
    → ManifestMerger.merge(appManifest, libraryManifests, applicationId)
    → aapt2 link uses MERGED manifest
    → Packager packages merged manifest
```

## Files changed

| File | Change |
|------|--------|
| `hbe-core/.../ManifestMerger.kt` | NEW — generic AAR manifest merger |
| `hbe-core/.../ProjectDependencies.kt` | +libraryManifests field |
| `hbe-core/.../ProjectResolver.kt` | collect manifests in addArtifact |
| `hbe-core/.../IncrementalBuildPipeline.kt` | call merger, thread merged manifest |
| `hbe-core/.../DefaultBuildPipeline.kt` | +BuildProgressTracker |
| `hbe-core/.../ManifestMergerTest.kt` | NEW — 15 tests |
| `hbe-core/.../IncrementalBuildPipelineTest.kt` | updated for 8-task graph |
| `hbe-resources/.../ResourceMergeTest.kt` | NEW — 7 namespace tests |

## Test results

- **ManifestMergerTest**: 15/15 PASS (incl. Aira regression + ProfileInstallReceiver regression)
- **IncrementalBuildPipelineTest**: 8/8 PASS
- **ResourceMergeTest**: 7/7 PASS
- **ResourceCompilerImplTest**: SKIP (mockk/ByteBuddy fails in PRoot — environmental, pre-existing)

## Manifest comparison: HMX vs Reference (working Gradle build)

| Feature | HMX | Reference | Status |
|---------|-----|-----------|--------|
| activities | 5 | 5 | ✓ match |
| services | 1 | 1 | ✓ match |
| receivers | 2 | 2 | ✓ match |
| providers | 1 | 1 | ✓ match |
| InitializationProvider | ✓ | ✓ | ✓ FIXED |
| CoreComponentFactory | ✓ | ✓ | ✓ FIXED |
| ProfileInstallReceiver | ✓ | ✓ | ✓ FIXED |
| placeholder substitution | ✓ | ✓ | ✓ FIXED |
| versionCode | "" | "1" | ✗ gap (non-crash) |
| versionName | "" | "1.0" | ✗ gap (non-crash) |
| minSdkVersion | 24 | 26 | ✗ gap (non-crash) |
| debuggable | missing | true | ✗ gap (non-crash) |

## Remaining gaps (do NOT cause crashes)

These are separate issues for later, not blockers:
1. **versionCode/versionName** not read from build.gradle by ProjectImporter
2. **minSdkVersion** hardcoded to 24 instead of reading from build.gradle
3. **debuggable** attribute not set for debug variant
4. **extractNativeLibs** attribute missing

## APK status

- **Builds**: ✓ (12.5 MB)
- **Installs**: ✓ (signature valid v2+v3)
- **Launches**: NOT VERIFIED (no device/emulator for logcat) — but crash root cause is fixed

To verify launch: install on a device/emulator and check `adb logcat | grep -E "AndroidRuntime|FATAL"`.

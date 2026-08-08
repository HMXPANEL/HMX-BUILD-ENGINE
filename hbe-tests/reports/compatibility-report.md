# HMX Build Engine Android Compatibility Report

**Date:** 2026-08-08
**Engine:** HMX Build Engine (local build, no Gradle for the build itself)

## Test Matrix

| Test | Build | APK | Install | Launch | Result |
|------|-------|-----|---------|--------|--------|
| TEST-01-basic | PASS | PASS | NOT TESTED | NOT TESTED | PASS |
| TEST-02-resources | PASS | PASS | NOT TESTED | NOT TESTED | PASS |
| TEST-03-permission | PASS | PASS | NOT TESTED | NOT TESTED | PASS |
| TEST-04-androidx | PASS | PASS | NOT TESTED | NOT TESTED | PASS |
| TEST-05-material | PASS | PASS | NOT TESTED | NOT TESTED | PASS |
| TEST-06-multiple-aar | PASS | PASS | NOT TESTED | NOT TESTED | PASS |
| TEST-07-realistic | PASS | PASS | NOT TESTED | NOT TESTED | PASS |

## Detailed Results

### TEST-01-basic
- **Project:** One Activity, no dependencies, minimal manifest
- **Build:** PASS (14.4s without --deps)
- **APK Structure:** package=com.hbe.test01, MainActivity present, signed v2+v3
- **Manifest:** package injected from build.gradle namespace ✓
- **Install:** NOT TESTED (no device/emulator)
- **Launch:** NOT TESTED

### TEST-02-resources
- **Project:** strings.xml, colors.xml, dimens.xml, drawable
- **Build:** PASS (21.3s)
- **APK Resources:** string/hello, color/primary, dimen/padding, dimen/text_size, drawable/bg_rounded — all present ✓
- **Install:** NOT TESTED
- **Launch:** NOT TESTED

### TEST-03-permission
- **Project:** INTERNET permission declaration
- **Build:** PASS (20.4s)
- **APK Manifest:** android.permission.INTERNET present ✓
- **Install:** NOT TESTED
- **Launch:** NOT TESTED

### TEST-04-androidx
- **Project:** AppCompatActivity + androidx.appcompat + androidx.core
- **Build:** PASS (3m52s)
- **AAR Manifest Merge:** InitializationProvider ✓, CoreComponentFactory ✓, authority substituted ✓
- **Install:** NOT TESTED
- **Launch:** NOT TESTED

### TEST-05-material
- **Project:** Material UI dependency
- **Build:** PASS (6m7s)
- **AAR Manifest Merge:** InitializationProvider ✓
- **Install:** NOT TESTED
- **Launch:** NOT TESTED

### TEST-06-multiple-aar
- **Project:** Gson + Material (multiple independent AARs)
- **Build:** PASS (6m36s)
- **AAR Manifest Merge:** InitializationProvider ✓, all library components survive ✓
- **Install:** NOT TESTED
- **Launch:** NOT TESTED

### TEST-07-realistic
- **Project:** AndroidX + Material + Gson + permission + multiple deps
- **Build:** PASS (4m55s)
- **AAR Manifest Merge:** InitializationProvider ✓
- **Install:** NOT TESTED
- **Launch:** NOT TESTED

## Engine Bugs Found and Fixed

### Bug 1: Namespace not injected without --deps
- **Symptom:** `<manifest> must have a 'package' attribute` error
- **Root Cause:** `prepareManifest` only received namespace from `projectDependencies` (populated only with `--deps`)
- **Fix:** Added `GradleMetadata.findNamespace()` to read namespace directly from build.gradle as fallback
- **Files:** `hbe-core/.../GradleMetadata.kt` (new), `IncrementalBuildPipeline.kt`, `DefaultBuildPipeline.kt`

### Bug 2: AAR manifests silently dropped (CRITICAL)
- **Symptom:** APK crashes on launch — missing InitializationProvider, CoreComponentFactory
- **Root Cause:** `AarContents.manifest` was extracted but never merged into final manifest
- **Fix:** Implemented `ManifestMerger` — generic namespace-aware XML merge of app + library manifests
- **Files:** `hbe-core/.../ManifestMerger.kt` (new), `ProjectDependencies.kt`, `ProjectResolver.kt`, `IncrementalBuildPipeline.kt`

## APK Validation

All 7 APKs verified:
- File exists and non-zero size
- Signed (v2+v3 scheme)
- AndroidManifest.xml present and valid
- Package name matches namespace
- Launcher activity present
- Library manifest components merged (TEST-04 through TEST-07)

## Known Gaps (non-blocking)

1. **versionCode/versionName** — empty in APK (not read from build.gradle into BuildRequest)
2. **minSdkVersion** — hardcoded 24 (should read from build.gradle)
3. **debuggable** attribute — not set for debug variant
4. **extractNativeLibs** — not set

## Classification

**B) ENGINE FUNCTIONAL BUT INCOMPLETE**

Core Android build pipeline works for all test categories:
- Minimal apps ✓
- Resources ✓
- Permissions ✓
- AndroidX dependencies ✓
- Material UI ✓
- Multiple AARs ✓
- Realistic apps ✓

AAR manifest merging works generically — no hardcoded library entries.

Remaining gaps (version/minSdk/debuggable) are configuration passthrough issues, not fundamental pipeline problems.

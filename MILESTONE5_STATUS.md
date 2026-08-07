# Milestone 5 Complete — Aira Builds with HMX Engine

**Date:** 2026-08-08
**Commit:** (see git log)

## Result

Aira APK built end-to-end using ONLY HMX Build Engine (no Gradle for the build):

```
package: com.hmx.aira
minSdk: 24  targetSdk: 34  compileSdk: 34
size: 12.5 MB  signed: v2 + v3
warm incremental build: 8 cache hits, 0 misses, 70s (vs 419s cold)
```

APK verified: all 7 permissions present, classes.dex 8.6MB, all resources merged.

## What was fixed (production-readiness)

1. **Resource merger namespace bug** — `importNode` + `Transformer` lost `xmlns:*` declarations → aapt2 link crash. Rewrote to collect all namespace declarations from all sources and re-declare them on the merged root. Preserves xliff, tools, android, custom namespaces. Byte-validates output before writing.

2. **values.xml path** — aapt2 `--dir` expects `values/values.xml`, not root `values.xml`. Fixed.

3. **Classpath version leaking** — both winner (core:1.9.0) and loser (core:1.0.0) versions reached the classpath; javac saw the old one first → `setSilent(boolean)` not fixed. Added dedup in `ProjectResolver.collectArtifacts` keeping highest version per group:artifact with segment-based version comparison.

4. **Cache poisoning** — 404 HTML pages written directly to `.jar` path got cached; next build reused corrupt files. Fixed by downloading to a temp file first, moving to final path only on success.

5. **Encoding** — UTF-8 source files (em-dashes in comments) failed under POSIX locale (`file.encoding=ANSI_X3.4-1968`). Added `-encoding UTF-8` to both in-process and fallback javac paths.

6. **Live diagnostics** — `BuildProgressTracker` prints `[N/M]` stage markers, per-phase timing, 1s heartbeat (elapsed + memory + current file) for phases >5s, cache hit/miss counts.

7. **CLI error surfacing** — now prints up to 20 compiler/dependency error details on failure instead of just a count.

## Tests

`ResourceMergeTest`: 7 tests covering xliff, tools, android, custom namespaces, prefix collision across URIs, dedup-by-name, and byte-validity. All pass.

## Temporary patch applied to Aira

`app/src/main/res/drawable/bg_mic_button.xml` — drawable referenced by `activity_chat.xml` and `overlay_bottom_bar.xml` but missing from source (old AGP build had it only as artifact). Recreated as minimal oval/aira_accent placeholder with explanatory comment. Replace with original asset if recovered.

## Production-readiness audit findings

29 total findings (5 critical, 7 high, 10 medium, 7 low). 18 block production. Key non-build-blocking items: recovery system, daemon, R8 fallback to d8, main-dex naive heuristic, static `Hbe` facade. The `build` path is complete.

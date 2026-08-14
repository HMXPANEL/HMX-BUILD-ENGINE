package com.hbe.api

import java.nio.file.Path

interface Packager {
    fun packageApk(
        dexOutput: com.hbe.api.DexOutput,
        resources: com.hbe.api.ResourceBundle,
        manifest: Path,
        nativeLibs: List<Path> = emptyList(),
        assets: List<Path> = emptyList(),
        kotlinMetadataDir: Path? = null,
        outputDir: Path
    ): Path

    fun zipalign(apkFile: Path): Path

    /**
     * Assembles the `base` module of an Android App Bundle from its parts and
     * writes the resulting `.aab` (a ZIP container). [baseModuleDir] must contain
     * `manifest/AndroidManifest.xml`, `dex/classes.dex` and optionally `res/` and
     * `resources.pb`.
     */
    fun packageAab(baseModuleDir: Path, outputAab: Path): Path
}

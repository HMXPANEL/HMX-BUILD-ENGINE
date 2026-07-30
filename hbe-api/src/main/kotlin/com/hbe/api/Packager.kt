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
}

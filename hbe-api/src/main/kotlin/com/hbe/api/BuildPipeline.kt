package com.hbe.api

import com.hbe.api.dto.BuildResult

interface BuildPipeline {
    fun execute(context: BuildContext): BuildResult
}

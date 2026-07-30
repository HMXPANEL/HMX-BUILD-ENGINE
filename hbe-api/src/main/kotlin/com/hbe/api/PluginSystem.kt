package com.hbe.api

interface HbePlugin {
    val id: String
    val name: String
    val version: String
    fun onLoad(context: PluginContext)
    fun onPhaseStart(context: PhaseContext, phase: Phase) {}
    fun onPhaseEnd(context: PhaseContext, phase: Phase, result: PhaseResult) {}
    fun onUnload(context: PluginContext) {}
}

interface PluginContext {
    fun registerPhase(phase: Class<out Phase>)
    fun registerTool(name: String, path: java.nio.file.Path)
    fun registerDependencyResolver(resolver: DependencyResolver)
    val config: EngineConfig
    val logger: Logger
    val fileSystem: FileSystem
    fun addBuildListener(listener: BuildListener)
}

interface DependencyResolver {
    fun resolve(coordinate: com.hbe.api.dto.MavenCoordinate, repositories: List<String>): java.nio.file.Path?
}

interface BuildListener {
    fun onBuildStart(buildContext: BuildContext)
    fun onPhaseStart(buildContext: BuildContext, phase: Phase)
    fun onPhaseEnd(buildContext: BuildContext, phase: Phase, result: PhaseResult)
    fun onBuildEnd(buildContext: BuildContext, result: com.hbe.api.dto.BuildResult)
    fun onBuildError(buildContext: BuildContext, error: com.hbe.api.dto.BuildError)
}

interface PluginLoader {
    fun loadPlugins(pluginDir: java.nio.file.Path): List<HbePlugin>
    fun getPluginForPhase(phase: Phase): HbePlugin?
}

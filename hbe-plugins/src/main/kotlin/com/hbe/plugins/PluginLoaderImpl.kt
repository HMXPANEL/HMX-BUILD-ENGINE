package com.hbe.plugins

import com.hbe.api.*
import com.hbe.api.exception.BuildException
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.ServiceLoader

class PluginLoaderImpl(
    private val logger: Logger
) : PluginLoader {

    private var loadedPlugins: List<HbePlugin> = emptyList()

    override fun loadPlugins(pluginDir: Path): List<HbePlugin> {
        if (!java.nio.file.Files.isDirectory(pluginDir)) {
            logger.info("Plugin directory not found", mapOf("dir" to pluginDir.toString()))
            loadedPlugins = emptyList()
            return loadedPlugins
        }

        val pluginJars = java.nio.file.Files.list(pluginDir)
            .filter { it.toString().endsWith(".jar") }
            .toList()

        if (pluginJars.isEmpty()) {
            loadedPlugins = emptyList()
            return loadedPlugins
        }

        val urls = pluginJars.map { it.toUri().toURL() }.toTypedArray()
        val classLoader = URLClassLoader(urls, javaClass.classLoader)

        val plugins = ServiceLoader.load(HbePlugin::class.java, classLoader)
            .toList()

        logger.info("Plugins loaded", mapOf("count" to plugins.size.toString()))
        loadedPlugins = plugins
        return plugins
    }

    override fun getPluginForPhase(phase: Phase): HbePlugin? {
        return loadedPlugins.firstOrNull { plugin ->
            try {
                plugin::class.java.methods.any { method ->
                    method.parameterTypes.contains(Phase::class.java)
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}

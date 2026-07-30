package com.hbe.api.dto

data class MavenCoordinate(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val classifier: String? = null,
    val extension: String? = null
) {
    val effectiveExtension: String
        get() = extension ?: "jar"

    fun toPath(): String {
        val base = "$groupId/$artifactId/$version/$artifactId-$version"
        return if (classifier != null) "$base-$classifier.$effectiveExtension" else "$base.$effectiveExtension"
    }

    fun toNotation(): String {
        val base = "$groupId:$artifactId:$version"
        return if (classifier != null) "$base:$classifier" else base
    }

    override fun toString(): String = toNotation()

    companion object {
        fun parse(notation: String): MavenCoordinate {
            val parts = notation.split(":")
            return when (parts.size) {
                3 -> MavenCoordinate(parts[0], parts[1], parts[2])
                4 -> MavenCoordinate(parts[0], parts[1], parts[2], classifier = parts[3])
                else -> throw IllegalArgumentException("Invalid Maven coordinate: $notation")
            }
        }
    }
}

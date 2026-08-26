package mihon.gradle

import org.gradle.api.Project

interface BuildConfig {
    val includeTelemetry: Boolean
    val enableUpdater: Boolean
    val includeDependencyInfo: Boolean
}

val Project.Config: BuildConfig get() = object : BuildConfig {
    // Recto Leaf: telemetry and the update checker are permanently off (personal-use fork).
    override val includeTelemetry: Boolean = false
    override val enableUpdater: Boolean = false
    override val includeDependencyInfo: Boolean = project.hasProperty("include-dependency-info")
}

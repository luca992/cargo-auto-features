package io.github.luca992.cargoautofeatures.ide

import com.intellij.execution.CommonProgramRunConfigurationParameters
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.luca992.cargoautofeatures.CommandRewriter
import io.github.luca992.cargoautofeatures.CommandTokenizer
import io.github.luca992.cargoautofeatures.FeatureResolver
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Rewrites Cargo run configurations so the command carries the features the addressed test
 * needs. The Rust plugin stores the full cargo command line in
 * CommonProgramRunConfigurationParameters.programParameters and rebuilds its parsed form on
 * every setProgramParameters call, so no plugin classes are touched.
 */
object CargoAutoFeatures {

    private val LOG = Logger.getInstance(CargoAutoFeatures::class.java)
    private const val CARGO_TYPE_ID = "CargoCommandRunConfiguration"
    private val notified = ConcurrentHashMap.newKeySet<String>()

    fun process(project: Project, configuration: RunConfiguration) {
        try {
            val settings = AutoFeaturesSettings.instance()
            if (!settings.state.enabled) return
            if (configuration.type.id != CARGO_TYPE_ID) return
            val parameters = configuration as? CommonProgramRunConfigurationParameters ?: return
            val command = parameters.programParameters?.takeIf { it.isNotBlank() } ?: return
            val workingDirectory = parameters.workingDirectory
                ?.removePrefix("file://")
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Path.of(it) }.getOrNull() }
                ?.takeIf { Files.isDirectory(it) }
                ?: return

            val resolver = FeatureResolver(settings.extraFeatures())
            val rewritten = CommandRewriter.rewrite(command) { spec ->
                resolver.resolve(workingDirectory, spec)
            } ?: return

            parameters.programParameters = rewritten
            LOG.info("rewrote command of '${configuration.name}': $command -> $rewritten")
            if (settings.state.showNotification) notify(project, configuration.name, rewritten)
        } catch (t: Throwable) {
            LOG.warn("feature injection failed", t)
        }
    }

    private fun notify(project: Project, configurationName: String, command: String) {
        val tokens = CommandTokenizer.tokenize(command)
        val features = tokens.getOrNull(tokens.indexOf("--features") + 1) ?: return
        if (!notified.add("$configurationName|$features")) return
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Cargo Auto Features")
            .createNotification(
                "Cargo features added",
                "'$configurationName' now runs with --features $features",
                NotificationType.INFORMATION,
            )
            .notify(project)
    }
}

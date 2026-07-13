package io.github.luca992.cargoautofeatures.ide

import com.intellij.execution.ExecutionListener
import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project

/** Rewrites configurations as soon as the gutter or context action creates them. */
class RunConfigurationAddedListener(private val project: Project) : RunManagerListener {
    override fun runConfigurationAdded(settings: RunnerAndConfigurationSettings) {
        CargoAutoFeatures.process(project, settings.configuration)
    }
}

/** Re-resolves features on every launch so edited configurations and sources stay correct. */
class ProcessScheduledListener(private val project: Project) : ExecutionListener {
    override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
        val settings = env.runnerAndConfigurationSettings ?: return
        CargoAutoFeatures.process(project, settings.configuration)
    }
}

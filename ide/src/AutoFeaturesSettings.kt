package io.github.luca992.cargoautofeatures.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import io.github.luca992.cargoautofeatures.ExtraFeature

@Service(Service.Level.APP)
@State(name = "CargoAutoFeaturesSettings", storages = [Storage("cargoAutoFeatures.xml")])
class AutoFeaturesSettings : PersistentStateComponent<AutoFeaturesSettings.State> {

    class State {
        var enabled: Boolean = true

        /**
         * Comma separated features added to test runs when the package declares them.
         * A bare name applies to every package; `package:feature` to one package.
         */
        var extraTestFeatures: String = "mock"

        var showNotification: Boolean = true
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun extraFeatures(): List<ExtraFeature> = ExtraFeature.parseList(state.extraTestFeatures)

    companion object {
        fun instance(): AutoFeaturesSettings =
            ApplicationManager.getApplication().getService(AutoFeaturesSettings::class.java)
    }
}

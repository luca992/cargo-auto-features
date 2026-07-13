package io.github.luca992.cargoautofeatures.ide

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

class AutoFeaturesConfigurable : BoundConfigurable("Cargo Auto Features") {

    override fun createPanel(): DialogPanel {
        val state = AutoFeaturesSettings.instance().state
        return panel {
            row {
                checkBox("Add missing --features to Cargo test run configurations")
                    .bindSelected(state::enabled)
            }
            row("Extra test features:") {
                textField()
                    .bindText(state::extraTestFeatures)
                    .comment(
                        "Comma separated, added when the package declares them. " +
                            "A bare name applies to every package, package:feature to one " +
                            "(example: mock, my-crate:extra-feature)",
                    )
            }
            row {
                checkBox("Notify when features are added")
                    .bindSelected(state::showNotification)
            }
        }
    }
}

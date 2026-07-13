package io.github.luca992.cargoautofeatures

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Exercises resolution against a fixture mirroring a cargo workspace with glob members. */
class FeatureResolverTest {

    private val root: Path = Files.createTempDirectory("resolver-test")

    @AfterTest
    fun cleanup() {
        root.toFile().walkBottomUp().forEach { it.delete() }
    }

    private fun write(relative: String, content: String) {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    private fun setUpWorkspace() {
        write(
            "Cargo.toml",
            """
            [workspace]
            members = [
                "crates/*",
                "libs/other-crate",
            ]
            """.trimIndent(),
        )
        write(
            "crates/sample-app/Cargo.toml",
            """
            [package]
            name = "sample-app"

            [features]
            gated-feature = ["dep:serde"]
            mock = ["mockall"]
            unrelated = []
            """.trimIndent(),
        )
        write(
            "crates/sample-app/src/lib.rs",
            """
            #[cfg(feature = "gated-feature")]
            pub mod gated_module;
            """.trimIndent(),
        )
        write(
            "crates/sample-app/src/gated_module.rs",
            """
            #[cfg(test)]
            mod tests {
                #[test]
                fn some_test() {}
            }
            """.trimIndent(),
        )
        write(
            "libs/other-crate/Cargo.toml",
            """
            [package]
            name = "other-crate"

            [features]
            extra-feature = []

            [[test]]
            name = "integration"
            required-features = ["extra-feature"]
            """.trimIndent(),
        )
        write(
            "libs/other-crate/src/lib.rs",
            """
            #[cfg(feature = "extra-feature")]
            pub mod extra;
            """.trimIndent(),
        )
        write("libs/other-crate/tests/integration.rs", "#[test]\nfn works() {}\n")
    }

    @Test
    fun resolves_cfg_gate_plus_declared_extra_feature_through_glob_member() {
        setUpWorkspace()
        val resolver = FeatureResolver(ExtraFeature.parseList("mock"))
        val spec = TargetSpec(
            packageName = "sample-app",
            manifestDir = null,
            kind = TargetKind.LIB,
            targetName = null,
            testPathSegments = listOf("gated_module", "tests", "some_test"),
        )
        assertEquals(setOf("gated-feature", "mock"), resolver.resolve(root, spec))
    }

    @Test
    fun extra_features_apply_only_when_declared_by_the_package() {
        setUpWorkspace()
        val resolver = FeatureResolver(ExtraFeature.parseList("mock"))
        val spec = TargetSpec(
            packageName = "other-crate",
            manifestDir = null,
            kind = TargetKind.LIB,
            targetName = null,
            testPathSegments = listOf("extra", "tests", "some_test"),
        )
        assertEquals(setOf("extra-feature"), resolver.resolve(root, spec))
    }

    @Test
    fun integration_test_target_gets_required_features() {
        setUpWorkspace()
        val resolver = FeatureResolver(emptyList())
        val spec = TargetSpec(
            packageName = "other-crate",
            manifestDir = null,
            kind = TargetKind.TEST,
            targetName = "integration",
            testPathSegments = listOf("works"),
        )
        assertEquals(setOf("extra-feature"), resolver.resolve(root, spec))
    }

    @Test
    fun package_scoped_extra_applies_only_to_that_package() {
        setUpWorkspace()
        val resolver = FeatureResolver(ExtraFeature.parseList("mock, other-crate:extra-feature"))

        val other = TargetSpec("other-crate", null, TargetKind.LIB, null, listOf("plain", "tests"))
        assertEquals(setOf("extra-feature"), resolver.resolve(root, other))

        val app = TargetSpec("sample-app", null, TargetKind.LIB, null, listOf("tests"))
        assertEquals(setOf("mock"), resolver.resolve(root, app))
    }

    @Test
    fun unknown_package_resolves_to_null() {
        setUpWorkspace()
        val resolver = FeatureResolver(ExtraFeature.parseList("mock"))
        val spec = TargetSpec("nonexistent", null, TargetKind.LIB, null, emptyList())
        assertNull(resolver.resolve(root, spec))
    }

    @Test
    fun ungated_test_without_extras_resolves_to_null() {
        setUpWorkspace()
        write(
            "crates/plain-crate/Cargo.toml",
            """
            [package]
            name = "plain-crate"
            """.trimIndent(),
        )
        write("crates/plain-crate/src/lib.rs", "#[cfg(test)]\nmod tests {}\n")
        val resolver = FeatureResolver(ExtraFeature.parseList("mock"))
        val spec = TargetSpec("plain-crate", null, TargetKind.LIB, null, listOf("tests"))
        assertNull(resolver.resolve(root, spec))
    }

    @Test
    fun working_directory_can_be_the_package_itself() {
        setUpWorkspace()
        val resolver = FeatureResolver(ExtraFeature.parseList("mock"))
        val spec = TargetSpec(
            packageName = "sample-app",
            manifestDir = null,
            kind = TargetKind.LIB,
            targetName = null,
            testPathSegments = listOf("gated_module"),
        )
        val packageDir = root.resolve("crates/sample-app")
        assertEquals(setOf("gated-feature", "mock"), resolver.resolve(packageDir, spec))
    }

    @Test
    fun sibling_package_found_by_walking_up_to_workspace_root() {
        setUpWorkspace()
        val resolver = FeatureResolver(emptyList())
        val spec = TargetSpec(
            packageName = "other-crate",
            manifestDir = null,
            kind = TargetKind.LIB,
            targetName = null,
            testPathSegments = listOf("extra"),
        )
        val otherPackageDir = root.resolve("crates/sample-app")
        assertEquals(setOf("extra-feature"), resolver.resolve(otherPackageDir, spec))
    }
}

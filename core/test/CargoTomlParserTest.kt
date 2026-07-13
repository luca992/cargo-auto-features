package io.github.luca992.cargoautofeatures

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CargoTomlParserTest {

    @Test
    fun parses_package_name_features_and_workspace_members() {
        val info = CargoTomlParser.parse(
            """
            [package]
            name = "sample-app"
            version = "0.1.0"

            [features]
            default = []
            gated-feature = [
                "dep:chrono",
                "other-crate/some-feature",
            ]
            mock = ["mockall", "other-crate/mock"] # trailing comment
            "quoted-feature" = []

            [dependencies]
            mockall = { workspace = true, optional = true }

            [workspace]
            members = [
                "crates/*",
                "libs/other-crate",
            ]
            """.trimIndent(),
        )
        assertEquals("sample-app", info.packageName)
        assertEquals(setOf("default", "gated-feature", "mock", "quoted-feature"), info.featureNames)
        assertEquals(listOf("crates/*", "libs/other-crate"), info.workspaceMembers)
    }

    @Test
    fun parses_lib_path_and_target_definitions() {
        val info = CargoTomlParser.parse(
            """
            [package]
            name = "svc"

            [lib]
            path = "src/custom_lib.rs"

            [[test]]
            name = "integration"
            path = "tests/integration/main.rs"
            required-features = ["online", "mock"]

            [[bin]]
            name = "tool"
            required-features = ["cli"]
            """.trimIndent(),
        )
        assertEquals("src/custom_lib.rs", info.libPath)
        val test = info.targets.single { it.kind == TargetKind.TEST }
        assertEquals("integration", test.name)
        assertEquals("tests/integration/main.rs", test.path)
        assertEquals(listOf("online", "mock"), test.requiredFeatures)
        val bin = info.targets.single { it.kind == TargetKind.BIN }
        assertEquals("tool", bin.name)
        assertEquals(listOf("cli"), bin.requiredFeatures)
    }

    @Test
    fun ignores_lookalike_sections_and_comments() {
        val info = CargoTomlParser.parse(
            """
            [package]
            name = "real-name"

            [package.metadata.docs]
            name = "not-the-package"

            [features]
            # a comment, not a feature
            actual = [] # trailing
            """.trimIndent(),
        )
        assertEquals("real-name", info.packageName)
        assertEquals(setOf("actual"), info.featureNames)
        assertTrue(info.targets.isEmpty())
    }
}

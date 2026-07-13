package io.github.luca992.cargoautofeatures

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RustSourceScannerTest {

    private val root: Path = Files.createTempDirectory("scanner-test")

    @AfterTest
    fun cleanup() {
        root.toFile().walkBottomUp().forEach { it.delete() }
    }

    private fun write(relative: String, content: String): Path {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        return path
    }

    @Test
    fun collects_feature_gate_on_module_declaration() {
        val lib = write(
            "src/lib.rs",
            """
            #[cfg(feature = "first-feature")]
            pub mod first_module;

            #[cfg(feature = "gated-feature")]
            pub mod gated_module;
            """.trimIndent(),
        )
        write(
            "src/gated_module.rs",
            """
            pub struct GatedThing;

            #[cfg(test)]
            mod tests {
                #[test]
                fn some_test() {}
            }
            """.trimIndent(),
        )
        val features = RustSourceScanner.collectChainFeatures(
            lib,
            listOf("gated_module", "tests", "some_test"),
        )
        assertEquals(setOf("gated-feature"), features)
    }

    @Test
    fun collects_gates_across_nested_inline_modules_and_fn() {
        val lib = write(
            "src/lib.rs",
            """
            #[cfg(all(feature = "outer", not(feature = "excluded")))]
            pub mod outer {
                #[cfg(any(test, feature = "inner"))]
                pub mod inner {
                    #[cfg(feature = "fn-only")]
                    #[test]
                    fn the_test() {}
                }
            }
            """.trimIndent(),
        )
        val features = RustSourceScanner.collectChainFeatures(lib, listOf("outer", "inner", "the_test"))
        assertEquals(setOf("outer", "inner", "fn-only"), features)
    }

    @Test
    fun follows_mod_rs_style_files_and_inner_attributes() {
        val lib = write(
            "src/lib.rs",
            """
            #[cfg(feature = "parent")]
            pub mod parent;
            """.trimIndent(),
        )
        write(
            "src/parent/mod.rs",
            """
            #![cfg(feature = "parent-inner")]
            pub mod scoring;
            """.trimIndent(),
        )
        write(
            "src/parent/scoring.rs",
            """
            #[cfg(test)]
            mod tests {
                fn scores() {}
            }
            """.trimIndent(),
        )
        val features = RustSourceScanner.collectChainFeatures(lib, listOf("parent", "scoring", "tests", "scores"))
        assertEquals(setOf("parent", "parent-inner"), features)
    }

    @Test
    fun descends_into_directory_of_non_root_module_file() {
        val lib = write("src/lib.rs", "pub mod api;\n")
        write(
            "src/api.rs",
            """
            #[cfg(feature = "child-feature")]
            pub mod child;
            """.trimIndent(),
        )
        write(
            "src/api/child.rs",
            """
            #[cfg(test)]
            mod tests {}
            """.trimIndent(),
        )
        val features = RustSourceScanner.collectChainFeatures(lib, listOf("api", "child", "tests"))
        assertEquals(setOf("child-feature"), features)
    }

    @Test
    fun honors_path_attribute_on_module_declaration() {
        val lib = write(
            "src/lib.rs",
            """
            #[cfg(feature = "special")]
            #[path = "custom/location.rs"]
            mod relocated;
            """.trimIndent(),
        )
        write(
            "src/custom/location.rs",
            """
            #[cfg(feature = "nested")]
            pub mod nested_mod {}
            """.trimIndent(),
        )
        val features = RustSourceScanner.collectChainFeatures(lib, listOf("relocated", "nested_mod"))
        assertEquals(setOf("special", "nested"), features)
    }

    @Test
    fun ignores_matches_inside_strings_comments_and_nested_scopes() {
        val lib = write(
            "src/lib.rs",
            """
            // mod decoy;
            /* #[cfg(feature = "comment")] mod decoy; */
            const S: &str = "mod decoy;";
            const R: &str = r#"mod decoy;"#;

            fn container() {
                #[cfg(feature = "nested-scope")]
                mod decoy {}
            }

            #[cfg(feature = "real")]
            mod decoy {}
            """.trimIndent(),
        )
        val features = RustSourceScanner.collectChainFeatures(lib, listOf("decoy"))
        assertEquals(setOf("real"), features)
    }

    @Test
    fun stops_at_unknown_segment_and_keeps_collected_features() {
        val lib = write(
            "src/lib.rs",
            """
            #[cfg(feature = "known")]
            pub mod known {
            }
            """.trimIndent(),
        )
        val features = RustSourceScanner.collectChainFeatures(lib, listOf("known", "missing", "deeper"))
        assertEquals(setOf("known"), features)
    }

    @Test
    fun cfg_attribute_parsing_handles_not_any_all() {
        assertEquals(
            setOf("a", "b"),
            RustSourceScanner.featuresInCfgAttribute("""#[cfg(all(feature = "a", any(feature = "b", test)))]"""),
        )
        assertEquals(
            emptySet(),
            RustSourceScanner.featuresInCfgAttribute("""#[cfg(not(feature = "a"))]"""),
        )
        assertEquals(
            setOf("kept"),
            RustSourceScanner.featuresInCfgAttribute("""#[cfg(all(not(any(feature = "no", feature = "nope")), feature = "kept"))]"""),
        )
        assertEquals(
            emptySet(),
            RustSourceScanner.featuresInCfgAttribute("""#[derive(Debug)]"""),
        )
    }
}

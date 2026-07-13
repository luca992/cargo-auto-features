package io.github.luca992.cargoautofeatures

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandRewriterTest {

    private fun rewriteWith(features: Collection<String>?, command: String): String? =
        CommandRewriter.rewrite(command) { features }

    @Test
    fun injects_features_after_subcommand_for_gutter_generated_test_command() {
        val command = "test --package sample-app --lib " +
            "gated_module::tests::some_test -- --exact"
        val rewritten = rewriteWith(listOf("gated-feature", "mock"), command)
        assertEquals(
            "test --features gated-feature,mock --package sample-app --lib " +
                "gated_module::tests::some_test -- --exact",
            rewritten,
        )
    }

    @Test
    fun captures_target_spec_from_command() {
        var captured: TargetSpec? = null
        CommandRewriter.rewrite("test --package sample-app --lib a::tests::b -- --exact") {
            captured = it
            null
        }
        assertEquals("sample-app", captured?.packageName)
        assertEquals(TargetKind.LIB, captured?.kind)
        assertEquals(listOf("a", "tests", "b"), captured?.testPathSegments)
    }

    @Test
    fun merges_with_existing_features_and_deduplicates() {
        val command = "test --package other-crate --lib api::tests::t --features online-tests -- --exact"
        val rewritten = rewriteWith(listOf("online-tests", "mock"), command)
        assertEquals(
            "test --features online-tests,mock --package other-crate --lib api::tests::t -- --exact",
            rewritten,
        )
    }

    @Test
    fun returns_null_when_all_needed_features_are_present() {
        val command = "test --features gated-feature,mock --package sample-app --lib m::tests::t"
        assertNull(rewriteWith(listOf("gated-feature", "mock"), command))
    }

    @Test
    fun returns_null_for_non_test_commands() {
        assertNull(rewriteWith(listOf("x"), "run --package sample-app --bin sample-app"))
        assertNull(rewriteWith(listOf("x"), "build --package sample-app"))
    }

    @Test
    fun returns_null_for_all_features_runs() {
        assertNull(rewriteWith(listOf("x"), "test --package p --all-features --lib m::t"))
    }

    @Test
    fun returns_null_for_doc_tests_and_workspace_runs() {
        assertNull(rewriteWith(listOf("x"), "test --package p --doc m"))
        assertNull(rewriteWith(listOf("x"), "test --workspace m"))
    }

    @Test
    fun returns_null_without_package_or_manifest_path() {
        assertNull(rewriteWith(listOf("x"), "test --lib m::tests::t"))
    }

    @Test
    fun supports_equals_style_flags() {
        var captured: TargetSpec? = null
        CommandRewriter.rewrite("test --package=my-crate --test=integration cases::t") {
            captured = it
            null
        }
        assertEquals("my-crate", captured?.packageName)
        assertEquals(TargetKind.TEST, captured?.kind)
        assertEquals("integration", captured?.targetName)
        assertEquals(listOf("cases", "t"), captured?.testPathSegments)
    }

    @Test
    fun supports_nextest_run_commands() {
        val command = "nextest run --package sample-app --lib m::tests::t"
        val rewritten = rewriteWith(listOf("f1"), command)
        assertEquals("nextest run --features f1 --package sample-app --lib m::tests::t", rewritten)
    }

    @Test
    fun extracts_test_path_from_nextest_filter_expression() {
        var captured: TargetSpec? = null
        CommandRewriter.rewrite("nextest run --package p -E test(=m::tests::t)") {
            captured = it
            null
        }
        assertEquals(listOf("m", "tests", "t"), captured?.testPathSegments)
    }

    @Test
    fun replaces_scattered_feature_flags_with_one_merged_flag() {
        val command = "test --package p --features a --lib m::t -F b"
        val rewritten = rewriteWith(listOf("a", "b", "c"), command)
        assertEquals("test --features a,b,c --package p --lib m::t", rewritten)
    }

    @Test
    fun extracts_test_path_from_harness_args_after_separator() {
        var captured: TargetSpec? = null
        CommandRewriter.rewrite(
            "test --package sample-app --lib -- gated_module::tests::some_test --exact",
        ) {
            captured = it
            null
        }
        assertEquals(listOf("gated_module", "tests", "some_test"), captured?.testPathSegments)
    }

    @Test
    fun harness_filter_skips_value_flags_before_the_path() {
        var captured: TargetSpec? = null
        CommandRewriter.rewrite("test -p my-crate --lib -- --test-threads 1 --skip slow m::tests::t --exact") {
            captured = it
            null
        }
        assertEquals(listOf("m", "tests", "t"), captured?.testPathSegments)
    }

    @Test
    fun rewrites_partially_injected_command_with_harness_filter() {
        val command = "test --features mock --package sample-app --lib -- gated_module::tests::some_test --exact"
        val rewritten = rewriteWith(listOf("gated-feature", "mock"), command)
        assertEquals(
            "test --features mock,gated-feature --package sample-app --lib -- gated_module::tests::some_test --exact",
            rewritten,
        )
    }

    @Test
    fun rewrite_is_idempotent() {
        val needed = listOf("gated-feature", "mock")
        val first = rewriteWith(needed, "test --package sample-app --lib m::tests::t -- --exact")!!
        assertNull(rewriteWith(needed, first))
    }

    @Test
    fun manifest_path_produces_manifest_dir_spec() {
        var captured: TargetSpec? = null
        CommandRewriter.rewrite("test --manifest-path crates/foo/Cargo.toml --lib m::t") {
            captured = it
            null
        }
        assertEquals("crates/foo", captured?.manifestDir)
    }

    @Test
    fun package_version_suffix_is_stripped() {
        var captured: TargetSpec? = null
        CommandRewriter.rewrite("test -p my-crate@0.3.1 --lib m::t") {
            captured = it
            null
        }
        assertEquals("my-crate", captured?.packageName)
    }
}

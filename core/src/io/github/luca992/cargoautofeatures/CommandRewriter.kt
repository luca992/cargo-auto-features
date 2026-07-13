package io.github.luca992.cargoautofeatures

/** Rewrites a Cargo run configuration command so it includes missing `--features`. */
object CommandRewriter {

    private val TEST_SUBCOMMANDS = setOf("test", "bench")

    /** Flags that consume the following token as their value. */
    private val VALUE_FLAGS = setOf(
        "--package", "-p", "--bin", "--test", "--bench", "--example",
        "--features", "-F", "--profile", "--manifest-path", "--target",
        "--target-dir", "--jobs", "-j", "--exclude", "--color",
        "--message-format", "-Z", "--config", "--lockfile-path",
        "-E", "--filter-expr", "--filterset", "-P", "--partition",
        "--archive-file", "--cargo-profile",
    )

    /** Test-harness flags (after `--`) that consume the following token as their value. */
    private val HARNESS_VALUE_FLAGS = setOf(
        "--skip", "--format", "--logfile", "--test-threads", "--color", "-Z", "--shuffle-seed",
    )

    /**
     * Returns the command with missing features inserted after the subcommand, or null when
     * nothing needs to change or the command cannot be analyzed. [resolveFeatures] supplies
     * the full set of features the addressed target needs, or null when unknown.
     */
    fun rewrite(command: String, resolveFeatures: (TargetSpec) -> Collection<String>?): String? {
        val tokens = CommandTokenizer.tokenize(command)
        if (tokens.isEmpty()) return null
        val subcommandLength = when {
            tokens[0] in TEST_SUBCOMMANDS -> 1
            tokens[0] == "nextest" && tokens.getOrNull(1) == "run" -> 2
            else -> return null
        }
        val separator = tokens.indexOf("--").let { if (it == -1) tokens.size else it }
        val cargoArgs = tokens.subList(subcommandLength, separator)
        val trailingArgs = tokens.subList(separator, tokens.size)

        var packageName: String? = null
        var packageCount = 0
        var manifestDir: String? = null
        var kind = TargetKind.DEFAULT
        var targetName: String? = null
        var doc = false
        var allFeatures = false
        var multiPackage = false
        val existingFeatures = LinkedHashSet<String>()
        var filter: String? = null
        var filterExpression: String? = null

        var i = 0
        while (i < cargoArgs.size) {
            val arg = cargoArgs[i]
            var consumedNext = false
            val eq = if (arg.startsWith("--")) arg.indexOf('=') else -1
            val flag = if (eq != -1) arg.substring(0, eq) else arg

            fun value(): String? {
                if (eq != -1) return arg.substring(eq + 1)
                val next = cargoArgs.getOrNull(i + 1) ?: return null
                consumedNext = true
                return next
            }

            when (flag) {
                "--package", "-p" -> {
                    packageName = value()
                    packageCount++
                }
                "--bin" -> {
                    kind = TargetKind.BIN
                    targetName = value()
                }
                "--test" -> {
                    kind = TargetKind.TEST
                    targetName = value()
                }
                "--bench" -> {
                    kind = TargetKind.BENCH
                    targetName = value()
                }
                "--example" -> {
                    kind = TargetKind.EXAMPLE
                    targetName = value()
                }
                "--lib" -> kind = TargetKind.LIB
                "--doc" -> doc = true
                "--all-features" -> allFeatures = true
                "--workspace", "--all" -> multiPackage = true
                "--features", "-F" -> value()?.let { existingFeatures += splitFeatureList(it) }
                "--manifest-path" -> manifestDir = value()?.let(::parentDirOf)
                "-E", "--filter-expr", "--filterset" -> filterExpression = value()
                in VALUE_FLAGS -> value()
                else -> if (!flag.startsWith("-") && filter == null) filter = arg
            }
            i += if (consumedNext) 2 else 1
        }

        if (doc || allFeatures || multiPackage || packageCount > 1) return null
        if (packageName == null && manifestDir == null) return null

        val testPath = filter
            ?: harnessFilter(trailingArgs)
            ?: filterExpression?.let(::extractTestPath)
        val segments = testPath?.split("::")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val spec = TargetSpec(packageName?.substringBefore('@'), manifestDir, kind, targetName, segments)

        val needed = resolveFeatures(spec) ?: return null
        val missing = needed.filter { it !in existingFeatures }
        if (missing.isEmpty()) return null

        val merged = existingFeatures + missing
        val newTokens = tokens.subList(0, subcommandLength) +
            listOf("--features", merged.joinToString(",")) +
            removeFeatureArgs(cargoArgs) +
            trailingArgs
        return CommandTokenizer.join(newTokens)
    }

    fun splitFeatureList(value: String): List<String> =
        value.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }

    private fun removeFeatureArgs(args: List<String>): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "--features" || arg == "-F" -> i += 2
                arg.startsWith("--features=") -> i += 1
                else -> {
                    result.add(arg)
                    i += 1
                }
            }
        }
        return result
    }

    private fun parentDirOf(manifestPath: String): String {
        val normalized = manifestPath.replace('\\', '/')
        val slash = normalized.lastIndexOf('/')
        return if (slash < 0) "." else normalized.substring(0, slash)
    }

    /**
     * The test path filter when it is passed to the test binary, as in
     * `test --package p --lib -- module::tests::name --exact`.
     */
    private fun harnessFilter(trailingArgs: List<String>): String? {
        var i = 1
        while (i < trailingArgs.size) {
            val arg = trailingArgs[i]
            when {
                arg in HARNESS_VALUE_FLAGS -> i += 2
                arg.startsWith("-") -> i += 1
                else -> return arg
            }
        }
        return null
    }

    private fun extractTestPath(filterExpression: String): String? =
        Regex("""test\(\s*[=~/]?\s*([A-Za-z0-9_]+(?:::[A-Za-z0-9_]+)*)""")
            .find(filterExpression)?.groupValues?.get(1)
}

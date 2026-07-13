package io.github.luca992.cargoautofeatures

data class TomlTargetDef(
    val kind: TargetKind,
    val name: String?,
    val path: String?,
    val requiredFeatures: List<String>,
)

data class CargoTomlInfo(
    val packageName: String?,
    val featureNames: Set<String>,
    val libPath: String?,
    val targets: List<TomlTargetDef>,
    val workspaceMembers: List<String>,
)

/**
 * Minimal Cargo.toml reader. Extracts the package name, feature names, target path overrides,
 * required-features, and workspace members. Not a general TOML parser: it understands the
 * section/key/string/array subset Cargo manifests use for these fields.
 */
object CargoTomlParser {

    private val SECTION_TARGET_KINDS = mapOf(
        "bin" to TargetKind.BIN,
        "test" to TargetKind.TEST,
        "bench" to TargetKind.BENCH,
        "example" to TargetKind.EXAMPLE,
    )

    fun parse(text: String): CargoTomlInfo {
        var packageName: String? = null
        val featureNames = mutableSetOf<String>()
        var libPath: String? = null
        val targets = mutableListOf<TomlTargetDef>()
        val workspaceMembers = mutableListOf<String>()

        var section = ""
        var currentTarget: MutableTarget? = null

        fun finishTarget() {
            currentTarget?.let { targets.add(it.build()) }
            currentTarget = null
        }

        var pendingKey: String? = null
        val pendingValue = StringBuilder()
        var pendingBalance = 0

        fun handleEntry(key: String, value: String) {
            when {
                section == "package" && key == "name" -> packageName = firstString(value)
                section == "features" -> featureNames.add(key)
                section == "lib" && key == "path" -> libPath = firstString(value)
                section == "workspace" && key == "members" -> workspaceMembers.addAll(allStrings(value))
                currentTarget != null -> when (key) {
                    "name" -> currentTarget?.name = firstString(value)
                    "path" -> currentTarget?.path = firstString(value)
                    "required-features" -> currentTarget?.requiredFeatures = allStrings(value)
                }
            }
        }

        for (rawLine in text.lineSequence()) {
            val line = stripComment(rawLine)
            if (pendingKey != null) {
                pendingValue.append('\n').append(line)
                pendingBalance += bracketBalance(line)
                if (pendingBalance <= 0) {
                    handleEntry(pendingKey, pendingValue.toString())
                    pendingKey = null
                    pendingValue.clear()
                    pendingBalance = 0
                }
                continue
            }
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("[")) {
                finishTarget()
                val isArrayOfTables = trimmed.startsWith("[[")
                val name = trimmed.trim('[', ']').trim()
                section = name
                if (isArrayOfTables) {
                    SECTION_TARGET_KINDS[name]?.let { currentTarget = MutableTarget(it) }
                }
                continue
            }
            val eq = indexOfTopLevelEquals(trimmed)
            if (eq <= 0) continue
            val key = trimmed.substring(0, eq).trim().trim('"', '\'')
            val value = trimmed.substring(eq + 1).trim()
            val balance = bracketBalance(value)
            if (balance > 0) {
                pendingKey = key
                pendingValue.append(value)
                pendingBalance = balance
            } else {
                handleEntry(key, value)
            }
        }
        finishTarget()

        return CargoTomlInfo(packageName, featureNames, libPath, targets, workspaceMembers)
    }

    private class MutableTarget(val kind: TargetKind) {
        var name: String? = null
        var path: String? = null
        var requiredFeatures: List<String> = emptyList()
        fun build() = TomlTargetDef(kind, name, path, requiredFeatures)
    }

    private fun stripComment(line: String): String {
        var inString = false
        var stringDelimiter = ' '
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString -> {
                    if (c == '\\' && stringDelimiter == '"') i++
                    else if (c == stringDelimiter) inString = false
                }
                c == '"' || c == '\'' -> {
                    inString = true
                    stringDelimiter = c
                }
                c == '#' -> return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun indexOfTopLevelEquals(line: String): Int {
        var inString = false
        var stringDelimiter = ' '
        for ((i, c) in line.withIndex()) {
            when {
                inString -> if (c == stringDelimiter) inString = false
                c == '"' || c == '\'' -> {
                    inString = true
                    stringDelimiter = c
                }
                c == '=' -> return i
            }
        }
        return -1
    }

    private fun bracketBalance(line: String): Int {
        var balance = 0
        var inString = false
        var stringDelimiter = ' '
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString -> {
                    if (c == '\\' && stringDelimiter == '"') i++
                    else if (c == stringDelimiter) inString = false
                }
                c == '"' || c == '\'' -> {
                    inString = true
                    stringDelimiter = c
                }
                c == '[' -> balance++
                c == ']' -> balance--
            }
            i++
        }
        return balance
    }

    private fun firstString(value: String): String? =
        Regex(""""([^"]*)"|'([^']*)'""").find(value)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }

    private fun allStrings(value: String): List<String> =
        Regex(""""([^"]*)"|'([^']*)'""").findAll(value)
            .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            .filter { it.isNotEmpty() }
            .toList()
}

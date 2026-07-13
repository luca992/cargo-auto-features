package io.github.luca992.cargoautofeatures

/** Kind of Cargo build target a command addresses. */
enum class TargetKind { LIB, BIN, TEST, BENCH, EXAMPLE, DEFAULT }

/** What a Cargo test command targets, extracted from its arguments. */
data class TargetSpec(
    val packageName: String?,
    val manifestDir: String?,
    val kind: TargetKind,
    val targetName: String?,
    val testPathSegments: List<String>,
)

object CommandTokenizer {
    fun tokenize(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var inToken = false
        for (c in command) {
            when {
                quote != null -> if (c == quote) quote = null else current.append(c)
                c == '"' || c == '\'' -> {
                    quote = c
                    inToken = true
                }
                c.isWhitespace() -> {
                    if (inToken) {
                        tokens.add(current.toString())
                        current.clear()
                        inToken = false
                    }
                }
                else -> {
                    current.append(c)
                    inToken = true
                }
            }
        }
        if (inToken) tokens.add(current.toString())
        return tokens
    }

    fun join(tokens: List<String>): String = tokens.joinToString(" ") { token ->
        if (token.isEmpty() || token.any { it.isWhitespace() || it == '"' }) {
            "\"" + token.replace("\"", "\\\"") + "\""
        } else {
            token
        }
    }
}

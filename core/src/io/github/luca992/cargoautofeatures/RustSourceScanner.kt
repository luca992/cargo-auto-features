package io.github.luca992.cargoautofeatures

import java.nio.file.Files
import java.nio.file.Path

/**
 * Collects the features referenced by `#[cfg(feature = "...")]` gates along the module chain
 * leading from a crate root file to a test path such as `a::b::tests::my_test`.
 *
 * Works on raw source text: comments and literal contents are masked before structural analysis,
 * attribute text is read from the original source. Modules declared through macros
 * (for example cfg_if!) are not followed. Features under `not(...)` are ignored; every branch
 * of `any(...)` is collected.
 */
object RustSourceScanner {

    fun collectChainFeatures(rootFile: Path, segments: List<String>): Set<String> {
        val features = LinkedHashSet<String>()
        var file = rootFile
        var isRootFile = true
        var source = readFile(file) ?: return features
        var masked = maskNonCode(source)
        var scopeStart = 0
        var scopeEnd = source.length
        features += innerCfgFeatures(source, masked, scopeStart, scopeEnd)

        segmentLoop@ for (segment in segments) {
            val item = findItem(source, masked, scopeStart, scopeEnd, segment) ?: break
            for (attribute in item.attributes) {
                features += featuresInCfgAttribute(attribute)
            }
            when (item.kind) {
                ItemKind.FN -> break@segmentLoop
                ItemKind.INLINE_MOD -> {
                    scopeStart = item.bodyStart
                    scopeEnd = item.bodyEnd
                }
                ItemKind.FILE_MOD -> {
                    val fileName = file.fileName.toString()
                    val childDir = if (isRootFile || fileName == "mod.rs") {
                        file.parent
                    } else {
                        file.parent.resolve(fileName.removeSuffix(".rs"))
                    }
                    val pathOverride = item.attributes.firstNotNullOfOrNull(::pathInAttribute)
                    val child = when {
                        pathOverride != null -> file.parent.resolve(pathOverride).normalize()
                        else -> listOf(
                            childDir.resolve("$segment.rs"),
                            childDir.resolve(segment).resolve("mod.rs"),
                        ).firstOrNull { Files.isRegularFile(it) }
                    } ?: break@segmentLoop
                    source = readFile(child) ?: break@segmentLoop
                    file = child
                    isRootFile = false
                    masked = maskNonCode(source)
                    scopeStart = 0
                    scopeEnd = source.length
                    features += innerCfgFeatures(source, masked, scopeStart, scopeEnd)
                }
            }
        }
        return features
    }

    /** Extracts positively required features from one attribute like `#[cfg(all(test, feature = "x"))]`. */
    fun featuresInCfgAttribute(attribute: String): Set<String> {
        val match = Regex("""^#!?\s*\[\s*cfg\s*\((.*)\)\s*]\s*$""", RegexOption.DOT_MATCHES_ALL)
            .find(attribute.trim()) ?: return emptySet()
        val features = LinkedHashSet<String>()
        collectFeatures(match.groupValues[1], negated = false, features)
        return features
    }

    private fun readFile(path: Path): String? = try {
        Files.readString(path)
    } catch (_: Exception) {
        null
    }

    private enum class ItemKind { INLINE_MOD, FILE_MOD, FN }

    private class Item(
        val kind: ItemKind,
        val attributes: List<String>,
        val bodyStart: Int = -1,
        val bodyEnd: Int = -1,
    )

    private fun findItem(source: String, masked: String, scopeStart: Int, scopeEnd: Int, name: String): Item? {
        val baseDepth = braceDepthAt(masked, scopeStart)
        for (keyword in listOf("mod", "fn")) {
            val regex = Regex("""(?<![A-Za-z0-9_])$keyword\s+${Regex.escape(name)}(?![A-Za-z0-9_])""")
            var match = regex.find(masked, scopeStart)
            while (match != null && match.range.first < scopeEnd) {
                val at = match.range.first
                if (braceDepthAt(masked, at) == baseDepth) {
                    var j = match.range.last + 1
                    while (j < scopeEnd && masked[j].isWhitespace()) j++
                    val next = masked.getOrNull(j)
                    val attributes by lazy { attributesBefore(source, masked, at, scopeStart) }
                    when {
                        keyword == "mod" && next == '{' -> {
                            val close = matchForward(masked, j, '{', '}') ?: return null
                            return Item(ItemKind.INLINE_MOD, attributes, j + 1, close)
                        }
                        keyword == "mod" && next == ';' -> return Item(ItemKind.FILE_MOD, attributes)
                        keyword == "fn" && (next == '(' || next == '<') -> return Item(ItemKind.FN, attributes)
                    }
                }
                match = match.next()
            }
        }
        return null
    }

    /** Attributes directly above a declaration, skipping visibility and function modifiers. */
    private fun attributesBefore(source: String, masked: String, declStart: Int, scopeStart: Int): List<String> {
        val attributes = mutableListOf<String>()
        var pos = declStart
        while (pos > scopeStart) {
            var k = pos - 1
            while (k >= scopeStart && masked[k].isWhitespace()) k--
            if (k < scopeStart) break
            when {
                masked[k] == ']' -> {
                    val open = matchBackward(masked, k, '[', ']') ?: return attributes
                    val beforeOpen = open - 1
                    if (beforeOpen >= scopeStart && masked[beforeOpen] == '#') {
                        attributes.add(source.substring(beforeOpen, k + 1))
                        pos = beforeOpen
                    } else {
                        return attributes
                    }
                }
                masked[k] == ')' -> {
                    pos = matchBackward(masked, k, '(', ')') ?: return attributes
                }
                else -> {
                    var s = k
                    while (s >= scopeStart && (masked[s].isLetterOrDigit() || masked[s] == '_')) s--
                    val word = masked.substring(s + 1, k + 1)
                    if (word in FN_MODIFIERS) pos = s + 1 else return attributes
                }
            }
        }
        return attributes
    }

    private val FN_MODIFIERS = setOf("pub", "crate", "unsafe", "async", "const", "extern")

    private fun pathInAttribute(attribute: String): String? =
        Regex("""^#\s*\[\s*path\s*=\s*"([^"]+)"\s*]\s*$""").find(attribute.trim())?.groupValues?.get(1)

    /** Features from `#![cfg(...)]` inner attributes at the top of a scope. */
    private fun innerCfgFeatures(source: String, masked: String, scopeStart: Int, scopeEnd: Int): Set<String> {
        val features = LinkedHashSet<String>()
        var pos = scopeStart
        while (pos < scopeEnd) {
            while (pos < scopeEnd && masked[pos].isWhitespace()) pos++
            if (pos + 2 >= scopeEnd || masked[pos] != '#' || masked[pos + 1] != '!' || masked[pos + 2] != '[') break
            val close = matchForward(masked, pos + 2, '[', ']') ?: break
            features += featuresInCfgAttribute(source.substring(pos, close + 1))
            pos = close + 1
        }
        return features
    }

    private fun collectFeatures(expression: String, negated: Boolean, out: MutableSet<String>) {
        var i = 0
        val n = expression.length

        fun skipWhitespace() {
            while (i < n && expression[i].isWhitespace()) i++
        }

        while (i < n) {
            val before = i
            skipWhitespace()
            val identStart = i
            while (i < n && (expression[i].isLetterOrDigit() || expression[i] == '_')) i++
            val ident = expression.substring(identStart, i)
            skipWhitespace()
            when {
                i < n && expression[i] == '(' -> {
                    val close = matchParenInExpression(expression, i)
                    val inner = expression.substring(i + 1, close)
                    i = close + 1
                    if (ident == "not") collectFeatures(inner, !negated, out)
                    else collectFeatures(inner, negated, out)
                }
                i < n && expression[i] == '=' -> {
                    i++
                    skipWhitespace()
                    if (i < n && expression[i] == '"') {
                        val end = expression.indexOf('"', i + 1)
                        val value = expression.substring(i + 1, if (end == -1) n else end)
                        i = if (end == -1) n else end + 1
                        if (ident == "feature" && !negated) out.add(value)
                    }
                }
            }
            skipWhitespace()
            if (i < n && expression[i] == ',') i++
            if (i == before) i++
        }
    }

    private fun matchParenInExpression(expression: String, open: Int): Int {
        var depth = 0
        var inString = false
        for (i in open until expression.length) {
            val c = expression[i]
            when {
                inString -> if (c == '"') inString = false
                c == '"' -> inString = true
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return expression.length - 1
    }

    private fun braceDepthAt(masked: String, index: Int): Int {
        var depth = 0
        for (i in 0 until index) {
            when (masked[i]) {
                '{' -> depth++
                '}' -> if (depth > 0) depth--
            }
        }
        return depth
    }

    private fun matchForward(masked: String, open: Int, openChar: Char, closeChar: Char): Int? {
        var depth = 0
        for (i in open until masked.length) {
            when (masked[i]) {
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun matchBackward(masked: String, close: Int, openChar: Char, closeChar: Char): Int? {
        var depth = 0
        for (i in close downTo 0) {
            when (masked[i]) {
                closeChar -> depth++
                openChar -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /**
     * Replaces the contents of comments and literals with spaces so structural scanning can
     * rely on braces, brackets, and keywords. Output has the same length as the input; string
     * delimiters are kept, contents are blanked.
     */
    fun maskNonCode(source: String): String {
        val out = source.toCharArray()
        val n = source.length

        fun blank(from: Int, to: Int) {
            for (j in from until minOf(to, n)) {
                if (out[j] != '\n') out[j] = ' '
            }
        }

        var i = 0
        while (i < n) {
            val c = source[i]
            when {
                c == '/' && i + 1 < n && source[i + 1] == '/' -> {
                    val start = i
                    while (i < n && source[i] != '\n') i++
                    blank(start, i)
                }
                c == '/' && i + 1 < n && source[i + 1] == '*' -> {
                    val start = i
                    var depth = 1
                    i += 2
                    while (i < n && depth > 0) {
                        when {
                            source[i] == '/' && i + 1 < n && source[i + 1] == '*' -> {
                                depth++
                                i += 2
                            }
                            source[i] == '*' && i + 1 < n && source[i + 1] == '/' -> {
                                depth--
                                i += 2
                            }
                            else -> i++
                        }
                    }
                    blank(start, i)
                }
                c == '"' -> {
                    val start = i + 1
                    i++
                    while (i < n && source[i] != '"') {
                        if (source[i] == '\\') i++
                        i++
                    }
                    blank(start, i)
                    if (i < n) i++
                }
                c == 'r' && i + 1 < n && (source[i + 1] == '"' || source[i + 1] == '#') &&
                    (i == 0 || !source[i - 1].isLetterOrDigit() && source[i - 1] != '_' || source[i - 1] == 'b' || source[i - 1] == 'c') -> {
                    var j = i + 1
                    var hashes = 0
                    while (j < n && source[j] == '#') {
                        hashes++
                        j++
                    }
                    if (j < n && source[j] == '"') {
                        val terminator = "\"" + "#".repeat(hashes)
                        val end = source.indexOf(terminator, j + 1)
                        blank(j + 1, if (end == -1) n else end)
                        i = if (end == -1) n else end + terminator.length
                    } else {
                        i++
                    }
                }
                c == '\'' -> {
                    when {
                        i + 2 < n && source[i + 1] != '\\' && source[i + 2] == '\'' -> {
                            blank(i + 1, i + 2)
                            i += 3
                        }
                        i + 1 < n && source[i + 1] == '\\' -> {
                            val start = i + 1
                            var j = i + 2
                            while (j < n && source[j] != '\'') j++
                            blank(start, j)
                            i = minOf(j + 1, n)
                        }
                        else -> i++
                    }
                }
                else -> i++
            }
        }
        return String(out)
    }
}

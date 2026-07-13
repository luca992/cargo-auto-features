package io.github.luca992.cargoautofeatures

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps a Cargo package name to its directory, starting from a run configuration's working
 * directory. Reads workspace member declarations instead of invoking cargo; results are
 * cached per root and verified against the manifest on each hit.
 */
object PackageLocator {

    private val cache = ConcurrentHashMap<Path, Map<String, Path>>()

    fun locate(workingDirectory: Path, packageName: String): Path? {
        packageNameAt(workingDirectory)?.takeIf { it == packageName }?.let { return workingDirectory }

        cache[workingDirectory]?.get(packageName)?.let { cached ->
            if (packageNameAt(cached) == packageName) return cached
        }

        val index = buildIndex(workingDirectory)
        cache[workingDirectory] = index
        return index[packageName]
    }

    private fun packageNameAt(dir: Path): String? = parseManifest(dir)?.packageName

    private fun parseManifest(dir: Path): CargoTomlInfo? = try {
        CargoTomlParser.parse(Files.readString(dir.resolve("Cargo.toml")))
    } catch (_: Exception) {
        null
    }

    private fun buildIndex(workingDirectory: Path): Map<String, Path> {
        val index = mutableMapOf<String, Path>()

        fun addMembersOf(root: Path): Boolean {
            val manifest = parseManifest(root) ?: return false
            manifest.packageName?.let { index[it] = root }
            for (member in manifest.workspaceMembers) {
                for (dir in expandMemberGlob(root, member)) {
                    parseManifest(dir)?.packageName?.let { index[it] = dir }
                }
            }
            return manifest.workspaceMembers.isNotEmpty()
        }

        if (addMembersOf(workingDirectory)) return index

        // The working directory may be a member; look upward for the workspace root.
        var parent = workingDirectory.parent
        var levels = 0
        while (parent != null && levels < 4) {
            if (Files.isRegularFile(parent.resolve("Cargo.toml")) && addMembersOf(parent)) return index
            parent = parent.parent
            levels++
        }
        return index
    }

    private fun expandMemberGlob(root: Path, pattern: String): List<Path> {
        var dirs = listOf(root)
        for (part in pattern.replace('\\', '/').split('/').filter { it.isNotEmpty() }) {
            dirs = when {
                !part.contains('*') -> dirs.mapNotNull { dir -> dir.resolve(part).takeIf(Files::isDirectory) }
                else -> {
                    val regex = globPartToRegex(part)
                    dirs.flatMap { dir -> subdirectories(dir).filter { regex.matches(it.fileName.toString()) } }
                }
            }
            if (dirs.isEmpty()) return emptyList()
        }
        return dirs.map { it.normalize() }
    }

    private fun globPartToRegex(part: String): Regex =
        Regex(part.split('*').joinToString("[^/]*") { Regex.escape(it) })

    private fun subdirectories(dir: Path): List<Path> = try {
        Files.list(dir).use { stream ->
            stream.filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }.toList()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

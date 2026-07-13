package io.github.luca992.cargoautofeatures

import java.nio.file.Files
import java.nio.file.Path

/** An always-on test feature, optionally scoped to one package. */
data class ExtraFeature(val packageName: String?, val feature: String) {
    companion object {
        /**
         * Parses a comma or space separated list. A bare name applies to every package that
         * declares the feature; `package:feature` applies to that package only.
         */
        fun parseList(spec: String): List<ExtraFeature> =
            spec.split(',', ' ').mapNotNull { entry ->
                val trimmed = entry.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val colon = trimmed.indexOf(':')
                if (colon > 0) ExtraFeature(trimmed.take(colon).trim(), trimmed.substring(colon + 1).trim())
                else ExtraFeature(null, trimmed)
            }.filter { it.feature.isNotEmpty() }
    }
}

/**
 * Resolves the features a Cargo command needs: `#[cfg(feature)]` gates on the module chain
 * to the addressed test, `required-features` of the addressed target, and the configured
 * extra test features when the package declares them.
 */
class FeatureResolver(private val extraTestFeatures: List<ExtraFeature>) {

    /** Returns the needed feature set, or null when nothing is needed or the package is unknown. */
    fun resolve(workingDirectory: Path, spec: TargetSpec): Set<String>? {
        val packageDir = spec.manifestDir?.let { workingDirectory.resolve(it).normalize() }
            ?: spec.packageName?.let { PackageLocator.locate(workingDirectory, it) }
            ?: return null
        val manifest = readManifest(packageDir) ?: return null

        val features = LinkedHashSet<String>()
        val target = selectTarget(manifest, spec)
        target?.requiredFeatures?.let { features += it }
        rootSourceFile(packageDir, manifest, spec, target)?.let {
            features += RustSourceScanner.collectChainFeatures(it, spec.testPathSegments)
        }
        features += extraTestFeatures
            .filter { it.packageName == null || it.packageName == manifest.packageName }
            .map { it.feature }
            .filter { it in manifest.featureNames }
        return features.ifEmpty { null }
    }

    private fun readManifest(packageDir: Path): CargoTomlInfo? = try {
        CargoTomlParser.parse(Files.readString(packageDir.resolve("Cargo.toml")))
    } catch (_: Exception) {
        null
    }

    private fun selectTarget(manifest: CargoTomlInfo, spec: TargetSpec): TomlTargetDef? =
        manifest.targets.firstOrNull { it.kind == spec.kind && it.name == spec.targetName }

    private fun rootSourceFile(
        packageDir: Path,
        manifest: CargoTomlInfo,
        spec: TargetSpec,
        target: TomlTargetDef?,
    ): Path? {
        target?.path?.let { return packageDir.resolve(it).takeIfFile() }
        val name = spec.targetName
        val candidates = when (spec.kind) {
            TargetKind.LIB, TargetKind.DEFAULT ->
                listOfNotNull(manifest.libPath?.let(packageDir::resolve) ?: packageDir.resolve("src/lib.rs"))
            TargetKind.TEST -> name?.let {
                listOf(packageDir.resolve("tests/$it.rs"), packageDir.resolve("tests/$it/main.rs"))
            }.orEmpty()
            TargetKind.BIN -> name?.let {
                listOfNotNull(
                    packageDir.resolve("src/bin/$it.rs"),
                    packageDir.resolve("src/bin/$it/main.rs"),
                    if (it == manifest.packageName) packageDir.resolve("src/main.rs") else null,
                )
            }.orEmpty()
            TargetKind.BENCH -> name?.let {
                listOf(packageDir.resolve("benches/$it.rs"), packageDir.resolve("benches/$it/main.rs"))
            }.orEmpty()
            TargetKind.EXAMPLE -> name?.let {
                listOf(packageDir.resolve("examples/$it.rs"), packageDir.resolve("examples/$it/main.rs"))
            }.orEmpty()
        }
        return candidates.firstOrNull { Files.isRegularFile(it) }
    }

    private fun Path.takeIfFile(): Path? = takeIf { Files.isRegularFile(it) }
}

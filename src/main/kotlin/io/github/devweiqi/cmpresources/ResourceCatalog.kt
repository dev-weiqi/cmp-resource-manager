package io.github.devweiqi.cmpresources

import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.extension
import kotlin.io.path.name

internal val SKIPPED_DIRECTORIES = setOf("build", "node_modules", "Pods", "out")
private val RESOURCE_TYPES = setOf("drawable", "font", "values", "files")

data class Resource(val root: Path, val file: Path, val size: Long, val animated: Boolean = false, val value: ResourceValue? = null) {
    val relativePath: String = root.relativize(file).toString().replace(oldChar = '\\', newChar = '/')
    val folder: String = relativePath.substringBefore(delimiter = '/')
    val type: String = value?.type ?: folder.substringBefore(delimiter = '-')
    val qualifier: String = folder.substringAfter(delimiter = '-', missingDelimiterValue = "default")
    val name: String = value?.key ?: relativePath.substringAfter(delimiter = '/').let {
        if (type == "drawable" || type == "font") it.substringBeforeLast(delimiter = '.') else it
    }

    fun matches(query: String, type: String, folder: String): Boolean =
        (type == "All types" || this.type == type) &&
            (folder == "All qualifiers" || this.folder == folder) &&
            (name.contains(other = query, ignoreCase = true) || relativePath.contains(other = query, ignoreCase = true) || value?.text?.contains(other = query, ignoreCase = true) == true)
}

data class ResourceGroup(val root: Path, val type: String, val name: String, val versions: List<Resource>) {
    val identity: Triple<Path, String, String> = Triple(root, type, name)
}

fun groupResources(resources: List<Resource>): List<ResourceGroup> = resources
    .groupBy { Triple(it.root, it.type, it.name) }
    .map { (key, versions) ->
        ResourceGroup(root = key.first, type = key.second, name = key.third, versions = versions.sortedBy { it.folder })
    }
    .sortedWith(compareBy({ it.root.toString() }, { it.name }))

data class ResourceSnapshot(val resources: List<Resource>, val warnings: List<String>)

fun scanResources(roots: List<Path>): ResourceSnapshot {
    val resources = sortedMapOf<Path, List<Resource>>()
    val warnings = mutableListOf<String>()
    for (root in roots) {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                private var resourceRoot: Path? = null

                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                    if (dir != root && resourceRoot == null && (dir.name.startsWith(prefix = ".") || dir.name in SKIPPED_DIRECTORIES)) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    if (dir.name == "composeResources" && resourceRoot == null) resourceRoot = dir
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val currentRoot = resourceRoot
                    if (currentRoot != null && attrs.isRegularFile && !file.name.startsWith(prefix = ".")) {
                        val animated = if (file.extension.equals(other = "webp", ignoreCase = true)) {
                            try {
                                Files.newInputStream(file).use { input ->
                                    val header = input.readNBytes(21)
                                    header.size == 21 && String(header, 0, 4) == "RIFF" && String(header, 8, 8) == "WEBPVP8X" && header[20].toInt() and 2 != 0
                                }
                            } catch (exception: IOException) {
                                warnings += "$file: ${exception.message}"
                                false
                            }
                        } else {
                            false
                        }
                        val resource = Resource(root = currentRoot, file = file, size = attrs.size(), animated = animated)
                        if (resource.type == "values" && file.extension.equals(other = "xml", ignoreCase = true)) {
                            try {
                                resources[file] = readResourceValues(path = file).map { resource.copy(value = it) }
                            } catch (exception: InterruptedIOException) {
                                throw exception
                            } catch (exception: IOException) {
                                warnings += "$file: ${exception.message}"
                            }
                        } else if (resource.type in RESOURCE_TYPES && resource.type != "values") {
                            resources[file] = listOf(resource)
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    warnings += "$file: ${exc.message}"
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (dir == resourceRoot) resourceRoot = null
                    if (exc != null) warnings += "$dir: ${exc.message}"
                    return FileVisitResult.CONTINUE
                }
            }
        )
    }
    return ResourceSnapshot(resources = resources.values.flatten(), warnings = warnings)
}

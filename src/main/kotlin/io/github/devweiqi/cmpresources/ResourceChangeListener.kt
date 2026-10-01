package io.github.devweiqi.cmpresources

import com.intellij.openapi.Disposable
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.Timer

class ResourceChangeListener(private val roots: () -> List<Path>, refresh: () -> Unit) : BulkFileListener, Disposable {
    @Volatile private var disposed = false
    private val timer = Timer(300) { if (!disposed) refresh() }.apply { isRepeats = false }

    override fun after(events: List<VFileEvent>) {
        if (disposed) return
        val projectRoots = roots()
        if (events.any { affectsResources(event = it, projectRoots = projectRoots) }) requestRefresh()
    }

    fun requestRefresh() {
        if (!disposed) timer.restart()
    }

    fun cancelPending() = timer.stop()

    override fun dispose() {
        disposed = true
        timer.stop()
    }

    private fun affectsResources(event: VFileEvent, projectRoots: List<Path>): Boolean {
        val paths = when (event) {
            is VFilePropertyChangeEvent -> if (event.isRename) listOf(event.oldPath, event.newPath) else return false
            is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
            is VFileCopyEvent -> listOf("${event.newParent.path}/${event.newChildName}")
            else -> listOf(event.path)
        }
        val directory = if (event is VFileCreateEvent) event.isDirectory else event.file?.isDirectory == true
        return paths.any { value ->
            val path = try {
                Path.of(value).normalize()
            } catch (_: InvalidPathException) {
                return@any false
            }
            val root = projectRoots.firstOrNull { path.startsWith(it) } ?: return@any false
            for (segment in root.relativize(path)) {
                val name = segment.toString()
                if (name == "composeResources") return@any true
                if (name.startsWith(prefix = ".") || name in SKIPPED_DIRECTORIES) return@any false
            }
            // A new, moved, or removed directory can contain an entire resource root.
            directory
        }
    }
}

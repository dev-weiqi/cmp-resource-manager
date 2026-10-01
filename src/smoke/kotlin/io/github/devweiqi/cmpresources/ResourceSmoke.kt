package io.github.devweiqi.cmpresources

import com.android.tools.adtui.webp.WebpMetadata
import com.intellij.core.CoreApplicationEnvironment
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.impl.VirtualFileManagerImpl
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.testFramework.LightVirtualFile
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.milliseconds

fun main(args: Array<String>) {
    WebpMetadata.ensureWebpRegistered()
    checkAnimation()
    checkPreviewBounds()
    checkVectorPreviews()
    checkPopupTargets()
    checkResourceActivation()
    checkPopupReadAccess()
    checkValueResources()
    checkResourceChanges()
    val fixture = Files.createTempDirectory("cmp-resources-check")
    try {
        val root = fixture.resolve("core/resources/src/commonMain/composeResources")
        listOf("drawable-hdpi/icon.webp", "drawable-xxxhdpi/icon.webp", "values-zh-rTW/strings.xml", "files/build/data.json").forEach { name ->
            val file = root.resolve(name)
            Files.createDirectories(file.parent)
            Files.writeString(file, if (name.endsWith(".xml")) "<resources><string name=\"hello\">Hello</string></resources>" else "fixture")
        }
        val generated = fixture.resolve("build/generated/composeResources/drawable/generated.png")
        Files.createDirectories(generated.parent)
        Files.writeString(generated, "excluded")
        val snapshot = scanResources(roots = listOf(fixture, root))
        check(snapshot.resources.size == 4 && snapshot.warnings.isEmpty()) { "Discovery must deduplicate roots and exclude build outputs" }
        check(snapshot.resources.count { it.matches(query = "ICON", type = "drawable", folder = "drawable-hdpi") } == 1)
        val groups = groupResources(resources = snapshot.resources)
        check(groups.size == 3)
        check(groups.single { it.name == "icon" }.versions.map { it.qualifier } == listOf("hdpi", "xxxhdpi"))
        val icon = snapshot.resources.first { it.type == "drawable" }
        val otherRoot = fixture.resolve("shared/src/commonMain/composeResources")
        val duplicateName = icon.copy(root = otherRoot, file = otherRoot.resolve("drawable-hdpi/icon.webp"))
        check(groupResources(resources = snapshot.resources + duplicateName).count { it.name == "icon" } == 2)
        check(groups.single { it.type == "files" }.name == "build/data.json")
        check(snapshot.resources.all { it.size == Files.size(it.file) })
        println("Discovery, grouping, source root isolation, qualifiers, sizes and search: OK")
        args.firstOrNull()?.let { project ->
            val actual = scanResources(roots = listOf(Path.of(project)))
            println("Project resources: ${actual.resources.size}, warnings: ${actual.warnings.size}")
            check(actual.resources.isNotEmpty()) { "Expected project composeResources" }
            actual.resources.firstOrNull { it.type == "drawable" && it.file.toString().endsWith(".xml") }?.let { resource ->
                val vector = readVectorPreview(path = resource.file, size = 124)
                check(vector.image.width in 1..124 && vector.image.height in 1..124)
                checkNotNull(loadResourcePreview(path = resource.file, size = 44))
                println("Project vector XML: ${resource.name}, ${vector.dimensions}, thumbnail decoded")
                vector.image.flush()
            }
            actual.resources.firstOrNull { it.animated }?.let { resource ->
                val animation = checkNotNull(readWebpAnimation(path = resource.file, size = 512))
                check(animation.frames.size > 1)
                checkNotNull(loadResourcePreview(path = resource.file, size = 88))
                println("Project animated WebP: ${animation.frames.size} frames decoded without JCEF")
            }
        }
    } finally {
        Files.walk(fixture).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}

private fun checkPopupTargets() {
    SwingUtilities.invokeAndWait {
        val list = JList(arrayOf("first", "second", "third"))
        list.fixedCellWidth = 100
        list.fixedCellHeight = 50
        list.setSize(300, 250)
        list.selectedIndex = 0
        check(resourcePopupIndex(list = list, point = java.awt.Point(20, 75)) == 1) { "Right-click must target the clicked resource, not the previous selection" }
        check(resourcePopupIndex(list = list, point = java.awt.Point(20, 200)) == -1) { "Empty space must not target the nearest resource" }
        list.layoutOrientation = JList.HORIZONTAL_WRAP
        list.visibleRowCount = -1
        list.setSize(200, 200)
        check(resourcePopupIndex(list = list, point = java.awt.Point(120, 20)) == 1)
        check(resourcePopupIndex(list = list, point = java.awt.Point(120, 75)) == -1) { "An empty grid cell must not target another variant" }
        val empty = JList<String>()
        check(resourcePopupIndex(list = empty, point = java.awt.Point(0, 0)) == -1)
    }
    println("Resource popup targets: clicked rows, variant grid, empty space and empty lists: OK")
}

private fun checkVectorPreviews() {
    val file = Files.createTempFile("cmp-vector-check", ".xml")
    val header = """<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="12dp" android:viewportWidth="24" android:viewportHeight="12">"""

    fun render(body: String): VectorPreview {
        Files.writeString(file, "$header$body</vector>")
        return readVectorPreview(path = file, size = 96)
    }

    fun rejected(xml: String) {
        Files.writeString(file, xml)
        try {
            readVectorPreview(path = file, size = 96)
            error("Unsafe or unsupported XML must not render")
        } catch (_: IOException) {
            // Expected rejection at the preview boundary.
        }
    }
    try {
        val result = render("""<path android:fillColor="#FF0000" android:pathData="M2,2 L10,2 L10,10 L2,10 Z"/>""")
        check(result.image.width == 96 && result.image.height == 48)
        check(result.dimensions == "24dp × 12dp")
        check(result.image.getRGB(20, 20) == 0xFFFF0000.toInt())
        check(result.image.getRGB(90, 20) ushr 24 == 0)
        val icon = checkNotNull(loadResourcePreview(path = file, size = 44))
        check(icon.iconWidth == 44 && icon.iconHeight == 22) { "List thumbnails must render vector XML" }
        val group = render("""<group android:translateX="12"><path android:fillColor="#0000FF" android:fillAlpha="0.5" android:pathData="M0,0h8v12h-8z"/></group>""")
        check(group.image.getRGB(8, 20) ushr 24 == 0)
        check(group.image.getRGB(56, 20) ushr 24 in 127..128)
        check(group.image.getRGB(56, 20) and 0xFFFFFF == 0x0000FF)
        val clipped = render("""<group><clip-path android:pathData="M0,0h12v12H0z"/><path android:fillColor="#00FF00" android:pathData="M0,0h24v12H0z"/></group>""")
        check(clipped.image.getRGB(20, 20) == 0xFF00FF00.toInt())
        check(clipped.image.getRGB(76, 20) ushr 24 == 0)
        val stroke = render("""<path android:strokeColor="#FF0000" android:strokeWidth="2" android:pathData="M2,6 L22,6"/>""")
        check(stroke.image.getRGB(48, 24) == 0xFFFF0000.toInt())
        check(stroke.image.getRGB(48, 4) ushr 24 == 0)
        rejected("<resources/>")
        rejected("$header<path></vector>")
        rejected("""$header<path android:fillColor="@color/red" android:pathData="M0,0h24v12H0z"/></vector>""")
        rejected(header.replace("24dp", "0dp") + "</vector>")
        rejected(header.replace("viewportWidth=\"24\"", "viewportWidth=\"NaN\"") + "</vector>")
        rejected("""<!DOCTYPE vector [<!ENTITY secret SYSTEM "file:///etc/passwd">]>$header&secret;</vector>""")
        rejected(header + "<group>".repeat(65) + "</group>".repeat(65) + "</vector>")
        rejected(header + " ".repeat(1024 * 1024) + "</vector>")
        println("Vector XML: thumbnail rendering, dimensions, aspect ratio, transparency, groups, clipping, strokes, malformed input, references, XXE and limits: OK")
    } finally {
        Files.deleteIfExists(file)
    }
}

private fun checkResourceChanges() {
    val lifetime = Disposer.newDisposable()
    val environment = CoreApplicationEnvironment(lifetime)
    // Native create events need a name table; keep the check independent of the IDE's persistent VFS.
    environment.application.picoContainer.unregisterComponent(VirtualFileManager::class.java.name)
    environment.registerApplicationService(
        VirtualFileManager::class.java,
        object : VirtualFileManagerImpl(emptyList(), environment.application.messageBus) {
            private val names = mutableListOf<String>()

            override fun storeName(name: String): Int {
                names += name
                return names.lastIndex
            }

            override fun getVFileName(id: Int): CharSequence = names[id]
        }
    )
    val fixture = Files.createTempDirectory("cmp-auto-refresh-check")
    val project = fixture.resolve("project")
    val root = project.resolve("shared/src/commonMain/composeResources")
    val file = root.resolve("drawable/icon.png")
    val updates = LinkedBlockingQueue<ResourceSnapshot>()
    lateinit var listener: ResourceChangeListener

    fun changed(vararg events: VFileEvent): ResourceSnapshot {
        SwingUtilities.invokeAndWait { environment.application.messageBus.syncPublisher(VirtualFileManager.VFS_CHANGES).after(events.toList()) }
        return checkNotNull(updates.poll(3, TimeUnit.SECONDS)) { "Relevant VFS event did not refresh resources" }
    }
    SwingUtilities.invokeAndWait {
        listener = ResourceChangeListener(roots = { listOf(project) }, refresh = {
            check(SwingUtilities.isEventDispatchThread())
            updates.add(scanResources(roots = listOf(project)))
        })
        environment.application.messageBus.connect(lifetime).subscribe(VirtualFileManager.VFS_CHANGES, listener)
    }
    try {
        Files.createDirectories(file.parent)
        Files.writeString(file, "first")
        val created = changed(*Array(20) { VFileCreateEvent(null, eventFile(path = file.parent, directory = true), "icon.png", false, null, null, null) })
        check(created.resources.single().name == "icon")
        Files.writeString(file, "updated content")
        val updated = changed(VFileContentChangeEvent(null, eventFile(path = file), 1, 2))
        check(updated.resources.single().size == 15L)
        val selected = groupResources(resources = created.resources).single()
        check(groupResources(resources = updated.resources).single { it.identity == selected.identity }.versions.single().file == file)

        val renamed = file.resolveSibling("renamed.png")
        Files.move(file, renamed)
        check(changed(VFilePropertyChangeEvent(null, eventFile(path = file), VirtualFile.PROP_NAME, "icon.png", "renamed.png")).resources.single().name == "renamed")

        val outside = fixture.resolve("renamed.png")
        Files.move(renamed, outside)
        check(changed(VFileMoveEvent(null, eventFile(path = renamed), eventFile(path = fixture, directory = true))).resources.isEmpty())
        val returned = file.parent.resolve("renamed.png")
        Files.move(outside, returned)
        check(changed(VFileMoveEvent(null, eventFile(path = outside), eventFile(path = file.parent, directory = true))).resources.single().file == returned)

        val copy = file.resolveSibling("copy.png")
        Files.copy(returned, copy)
        check(changed(VFileCopyEvent(null, eventFile(path = returned), eventFile(path = file.parent, directory = true), "copy.png")).resources.size == 2)
        Files.delete(copy)
        check(changed(VFileDeleteEvent(null, eventFile(path = copy))).resources.single().file == returned)

        val module = project.resolve("newModule")
        val nested = module.resolve("src/commonMain/composeResources/files/data.json")
        Files.createDirectories(nested.parent)
        Files.writeString(nested, "{}")
        check(changed(VFileCreateEvent(null, eventFile(path = project, directory = true), "newModule", true, null, null, null)).resources.size == 2)
        Files.walk(module).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        check(changed(VFileDeleteEvent(null, eventFile(path = module, directory = true))).resources.single().file == returned)

        SwingUtilities.invokeAndWait {
            listener.after(
                listOf(
                    VFileContentChangeEvent(null, eventFile(path = project.resolve("src/Example.kt")), 1, 2),
                    VFileDeleteEvent(null, eventFile(path = project.resolve("build/generated/composeResources"), directory = true)),
                    VFileDeleteEvent(null, eventFile(path = project.resolve(".gradle"), directory = true)),
                    VFileDeleteEvent(null, eventFile(path = fixture.resolve("project-other/composeResources"), directory = true))
                )
            )
        }
        check(updates.poll(500, TimeUnit.MILLISECONDS) == null) { "Unrelated changes triggered a refresh, or a burst was not coalesced" }
        val raw = root.resolve("files/build/data.json")
        Files.createDirectories(raw.parent)
        Files.writeString(raw, "{}")
        check(changed(VFileCreateEvent(null, eventFile(path = raw.parent, directory = true), "data.json", false, null, null, null)).resources.size == 2)
        SwingUtilities.invokeAndWait {
            listener.requestRefresh()
            listener.dispose()
            listener.requestRefresh()
        }
        check(updates.poll(500, TimeUnit.MILLISECONDS) == null) { "Disposed listener refreshed the project" }
        println("Automatic refresh: VFS create/edit/rename/move/copy/delete, whole roots, debounce, selection identity, exclusions and disposal: OK")
    } finally {
        SwingUtilities.invokeAndWait { listener.dispose() }
        Disposer.dispose(lifetime)
        Files.walk(fixture).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}

private fun eventFile(path: Path, directory: Boolean = false): VirtualFile = object : LightVirtualFile(path.fileName.toString()) {
    override fun getPath(): String = path.toString()

    override fun getParent(): VirtualFile? = path.parent?.let { eventFile(path = it, directory = true) }

    override fun isDirectory(): Boolean = directory
}

private fun checkValueResources() {
    val fixture = Files.createTempDirectory("cmp-values-check")
    try {
        val root = fixture.resolve("shared/src/commonMain/composeResources")
        val base = root.resolve("values/messages.xml")
        Files.createDirectories(base.parent)
        Files.writeString(
            base,
            """
            <resources>
                <string name="welcome">Welcome &amp; enjoy</string>
                <string-array name="welcome"><item>First</item><item><![CDATA[<Second>]]></item></string-array>
                <plurals name="messages"><item quantity="one">%1${'$'}d message</item><item quantity="other">%1${'$'}d messages</item></plurals>
                <string name="empty"></string>
                <string-array name="empty_array" />
                <string name="escaped">Line\nNext \u2605</string>
                <color name="ignored">#ffffff</color>
            </resources>
            """.trimIndent()
        )
        val localized = root.resolve("values-ja/translations.xml")
        Files.createDirectories(localized.parent)
        Files.writeString(
            localized,
            """
            <resources>
                <string name="welcome">ようこそ</string>
                <string-array name="welcome"><item>一番</item><item>二番</item></string-array>
                <plurals name="messages"><item quantity="other">%1${'$'}d 件</item></plurals>
            </resources>
            """.trimIndent()
        )
        val snapshot = scanResources(roots = listOf(fixture, root))
        check(snapshot.resources.size == 9 && snapshot.warnings.isEmpty())
        val groups = groupResources(resources = snapshot.resources)
        check(groups.size == 6) { "Group values by key and type, independent of XML filename" }
        val welcome = groups.single { it.type == "string" && it.name == "welcome" }
        check(welcome.versions.map { it.qualifier } == listOf("default", "ja"))
        check(welcome.versions.first().value?.text == "Welcome & enjoy")
        check(welcome.versions.first().value?.line == 2)
        check(welcome.versions.last().file == localized)
        check(welcome.versions.last().matches(query = "ようこそ", type = "string", folder = "All qualifiers"))
        check(welcome.versions.first().matches(query = "WELCOME", type = "string", folder = "All qualifiers"))
        val array = groups.single { it.type == "string-array" && it.name == "welcome" }
        check(array.versions.size == 2 && array.versions.first().value?.text == "[0]: First\n[1]: <Second>")
        val plurals = groups.single { it.type == "plurals" }
        check(plurals.versions.size == 2 && plurals.versions.first().value?.text == "one: %1${'$'}d message\nother: %1${'$'}d messages")
        check(groups.single { it.name == "empty" }.versions.single().value?.text == "")
        check(groups.single { it.name == "empty_array" }.versions.single().value?.text == "")
        check(groups.single { it.name == "escaped" }.versions.single().value?.text == "Line\\nNext \\u2605")
        SwingUtilities.invokeAndWait {
            val renderer = ResourceValueRenderer()
            val list = JList<Resource>()
            list.setSize(400, 600)
            for (resource in listOf(welcome.versions.first(), array.versions.last(), plurals.versions.first())) {
                renderer.getListCellRendererComponent(list, resource, 0, false, false)
                val content = renderer.components.filterIsInstance<JTextArea>().single()
                check(content.text == resource.value?.text && content.lineWrap)
                check(renderer.components.filterIsInstance<JLabel>().single().text.startsWith(resource.qualifier))
            }
        }
        Files.writeString(base.parent.resolve("broken.xml"), "<resources><string name=\"partial\">Must not leak</string><string>")
        Files.writeString(base.parent.resolve("external.xml"), "<!DOCTYPE resources [<!ENTITY xxe SYSTEM \"${base.toUri()}\">]><resources><string name=\"unsafe\">&xxe;</string></resources>")
        Files.writeString(base.parent.resolve("wrong_root.xml"), "<unexpected />")
        val isolated = scanResources(roots = listOf(fixture))
        check(isolated.resources == snapshot.resources && isolated.warnings.size == 3) { "Invalid XML must report warnings without leaking partial resources or breaking other files" }
        println("String, string-array and plurals: keys, locale grouping, values, source lines, search, rendering and XML safety: OK")
    } finally {
        Files.walk(fixture).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}

private fun checkAnimation() {
    val red = Base64.getDecoder().decode("UklGRhwAAABXRUJQVlA4TA8AAAAvAUAAAAcQ/Y/+ByKi/wEA")
    val blue = Base64.getDecoder().decode("UklGRhwAAABXRUJQVlA4TA8AAAAvAUAAAAcQ0f/+ByKi/wEA")
    val green = Base64.getDecoder().decode("UklGRhwAAABXRUJQVlA4TA8AAAAvAUAAEAfQ/4gCBiKi/wEA")
    val bytes = animationFile(
        frame(image = red, x = 0, duration = 40, flags = 2),
        frame(image = blue, x = 2, duration = 70, flags = 3),
        frame(image = green, x = 0, duration = 100, flags = 0)
    )
    val decoded = checkNotNull(decodeWebpAnimation(bytes = bytes, size = 4))
    check(decoded.loopCount == 2 && decoded.frames.size == 3)
    check(decoded.width == 4 && decoded.height == 2)
    check(decoded.frames.map { it.duration } == listOf(40.milliseconds, 70.milliseconds, 100.milliseconds))
    check(decoded.frames[0].image.getRGB(0, 0) == 0xFFFF0000.toInt())
    check(decoded.frames[0].image.getRGB(3, 0) == 0)
    check(decoded.frames[1].image.getRGB(0, 0) == 0xFFFF0000.toInt())
    check(decoded.frames[1].image.getRGB(3, 0) == 0xFF0000FF.toInt())
    val blended = decoded.frames[2].image.getRGB(0, 0)
    check((blended shr 16 and 255) in 126..129 && (blended shr 8 and 255) in 126..129)
    check(decoded.frames[2].image.getRGB(3, 0) == 0) { "Disposed frame must stay transparent even with an opaque ANIM background" }
    val thumbnail = checkNotNull(decodeWebpAnimation(bytes = bytes, size = 64, firstFrameOnly = true))
    check(thumbnail.frames.size == 1 && thumbnail.width == 4 && thumbnail.height == 2)
    check(decodeWebpAnimation(bytes = red, size = 64) == null)
    for (invalid in listOf(bytes.copyOf(newSize = bytes.size - 1), animationFile(frame(image = red, x = 4, duration = 40, flags = 2)))) {
        try {
            decodeWebpAnimation(bytes = invalid, size = 64)
            error("Malformed animation must fail")
        } catch (_: IOException) {
            // Expected validation failure.
        }
    }
    val fixture = Files.createTempDirectory("cmp-webp-check")
    try {
        val path = fixture.resolve("composeResources/files/animation.webp")
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
        check(scanResources(roots = listOf(fixture)).resources.single().animated)
        checkNotNull(loadResourcePreview(path = path, size = 44))
    } finally {
        Files.walk(fixture).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
    checkPlayback(animation = decoded)
    println("WebP frames, duration, loops, blending, disposal, bounds, thumbnail and pause/resume: OK")
}

private fun animationFile(vararg frames: ByteArray): ByteArray {
    val canvas = chunk(tag = "VP8X", payload = byteArrayOf(18, 0, 0, 0, 3, 0, 0, 1, 0, 0))
    // Match exported animations that suggest a white background but contain transparent frames.
    val header = chunk(tag = "ANIM", payload = byteArrayOf(-1, -1, -1, -1, 2, 0))
    val content = "WEBP".toByteArray() + canvas + header + frames.reduce { all, next -> all + next }
    return ByteBuffer.allocate(content.size + 8).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(content.size).put(content).array()
}

private fun frame(image: ByteArray, x: Int, duration: Int, flags: Int): ByteArray = chunk(
    tag = "ANMF",
    payload = byteArrayOf((x / 2).toByte(), 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, duration.toByte(), 0, 0, flags.toByte()) + image.copyOfRange(fromIndex = 12, toIndex = image.size)
)

private fun chunk(tag: String, payload: ByteArray): ByteArray = ByteBuffer.allocate(8 + payload.size + payload.size % 2)
    .order(ByteOrder.LITTLE_ENDIAN).put(tag.toByteArray()).putInt(payload.size).put(payload).array()

private fun checkPlayback(animation: WebpAnimation) {
    lateinit var player: WebpPlaybackPanel
    lateinit var button: JButton
    lateinit var position: JLabel
    val changed = CountDownLatch(1)
    SwingUtilities.invokeAndWait {
        player = WebpPlaybackPanel(animation = animation.copy(loopCount = 0))
        val footer = player.components.filterIsInstance<Container>().last()
        check(footer.components.filterIsInstance<JLabel>().single().text == "4 × 2 px")
        val controls = footer.components.filterIsInstance<Container>().last()
        button = controls.components.filterIsInstance<JButton>().single()
        position = controls.components.filterIsInstance<JLabel>().single()
        button.doClick(0)
        check(button.text == "Play")
    }
    Thread.sleep(120)
    SwingUtilities.invokeAndWait {
        check(position.text == "1 / 3") { "Paused animation advanced" }
        position.addPropertyChangeListener("text") { changed.countDown() }
        button.doClick(0)
        check(button.text == "Pause")
    }
    check(changed.await(2, TimeUnit.SECONDS)) { "Resumed animation did not advance" }
    SwingUtilities.invokeAndWait {
        player.dispose()
        check(!button.isEnabled)
    }
    lateinit var finite: WebpPlaybackPanel
    val completed = CountDownLatch(1)
    val counterLayouts = mutableSetOf<Pair<Int, Int>>()
    val counterLabels = mutableSetOf<String>()
    SwingUtilities.invokeAndWait {
        finite = WebpPlaybackPanel(animation = animation.copy(frames = List(12) { animation.frames[it % animation.frames.size].copy(duration = 10.milliseconds) }, loopCount = 1))
        val controls = finite.components.filterIsInstance<Container>().last().components.filterIsInstance<Container>().last()
        val toggle = controls.components.filterIsInstance<JButton>().single()
        val counter = controls.components.filterIsInstance<JLabel>().single()
        controls.setSize(400, 44)
        counter.addPropertyChangeListener("text") {
            controls.doLayout()
            counterLayouts += counter.width to toggle.x
            counterLabels += counter.text
        }
        toggle.addPropertyChangeListener("text") {
            if (it.newValue == "Play") completed.countDown()
        }
    }
    check(completed.await(2, TimeUnit.SECONDS)) { "Finite animation did not stop" }
    SwingUtilities.invokeAndWait {
        finite.dispose()
        check("9 / 12" in counterLabels && "10 / 12" in counterLabels)
        check(counterLayouts.size == 1) { "Frame counter width and playback button position shifted across digit counts: $counterLayouts" }
    }
}

private fun checkPreviewBounds() {
    SwingUtilities.invokeAndWait {
        for ((width, height) in listOf(200 to 100, 100 to 200)) {
            val source = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    source.setRGB(x, y, if (x < 10 || y < 10 || x >= width - 10 || y >= height - 10) 0xFFFF0000.toInt() else 0xFF0000FF.toInt())
                }
            }
            val preview = CheckerboardPreview()
            preview.icon = ImageIcon(source)
            preview.setSize(100, 100)
            val rendered = BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB)
            val graphics = rendered.createGraphics()
            try {
                preview.paint(graphics)
            } finally {
                graphics.dispose()
            }
            val edgeX = if (width > height) 1 else 26
            val edgeY = if (width > height) 26 else 1
            check(rendered.getRGB(edgeX, edgeY) == 0xFFFF0000.toInt()) { "Preview cropped image edges instead of fitting the viewport" }
            check(rendered.getRGB(50, 50) == 0xFF0000FF.toInt())
            check(rendered.getRGB(0, 0) != 0xFFFF0000.toInt()) { "Preview did not preserve its aspect ratio" }
        }
    }
    println("Landscape and portrait previews fit the viewport without cropping: OK")
}

private fun checkPopupReadAccess() {
    val lifetime = Disposer.newDisposable()
    var reading = false
    val environment = object : CoreApplicationEnvironment(lifetime) {
        override fun createApplication(parentDisposable: com.intellij.openapi.Disposable): com.intellij.mock.MockApplication =
            object : com.intellij.mock.MockApplication(parentDisposable) {
                override fun <T, E : Throwable?> runReadAction(computation: com.intellij.openapi.util.ThrowableComputable<T?, E?>): T? {
                    reading = true
                    return try {
                        computation.compute()
                    } finally {
                        reading = false
                    }
                }
            }
    }
    val project = object : com.intellij.mock.MockProject(environment.application.picoContainer, lifetime) {
        override fun <T : Any?> getService(serviceClass: Class<T>): T? {
            if (serviceClass == com.intellij.psi.PsiManager::class.java) {
                check(reading) { "Popup PSI lookup requires a read action, including on the EDT" }
            }
            return super.getService(serviceClass)
        }
    }
    project.registerService(com.intellij.psi.PsiManager::class.java, com.intellij.mock.MockPsiManager(project))
    try {
        SwingUtilities.invokeAndWait {
            listOf("icon.png", "vector.xml", "font.ttf", "data.json", "strings.xml").forEach { name ->
                resourcePopupPsi(project, LightVirtualFile(name))
            }
        }
        println("Popup PSI lookup acquires read access from the EDT for every resource type: OK")
    } finally {
        Disposer.dispose(lifetime)
    }
}

private fun checkResourceActivation() {
    SwingUtilities.invokeAndWait {
        val list = JList(arrayOf("first", "second"))
        list.fixedCellHeight = 50
        list.setSize(200, 150)
        val opened = mutableListOf<String>()
        installResourceActivation(list, isResourceRow = { _, event -> event.y >= 10 }, activate = opened::add)

        fun click(index: Int, count: Int = 1, button: Int = java.awt.event.MouseEvent.BUTTON1, y: Int = index * 50 + 25) {
            // Replay Swing's selection-before-click ordering.
            list.selectedIndex = index
            val event = java.awt.event.MouseEvent(list, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, 20, y, count, false, button)
            list.mouseListeners.forEach { it.mouseClicked(event) }
        }
        click(0)
        check(list.selectedIndex == 0 && opened.isEmpty())
        click(1)
        check(opened.isEmpty()) { "Changing rows must only select" }
        click(1)
        check(opened == listOf("second")) { "A second, separate click must open the selected row" }
        opened.clear()
        click(0)
        click(0, count = 2)
        check(opened == listOf("first")) { "A double click must also open exactly once" }
        opened.clear()
        click(0)
        list.focusListeners.forEach { it.focusLost(java.awt.event.FocusEvent(list, java.awt.event.FocusEvent.FOCUS_LOST)) }
        click(0)
        check(opened.isEmpty()) { "Returning focus must not immediately open a resource" }
        click(1, button = java.awt.event.MouseEvent.BUTTON3)
        check(opened.isEmpty())
        click(0, y = 5)
        click(0)
        check(opened.isEmpty()) { "Heading clicks must not arm navigation" }
        click(0, y = 125)
        click(0)
        check(opened.isEmpty()) { "Empty space must not arm navigation" }
    }
    println("Resource activation: select first, click again, switch rows, double click, focus, headings and empty space: OK")
}

package io.github.devweiqi.cmpresources

import com.android.ide.common.vectordrawable.VdPreview
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingWorker
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.name
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

data class VectorPreview(val image: BufferedImage, val dimensions: String)

fun readVectorPreview(path: Path, size: Int): VectorPreview {
    require(size in 1..4096)
    val maxBytes = 1024 * 1024
    if (Files.size(path) > maxBytes) throw IOException("Vector XML exceeds the 1 MiB preview limit")
    val bytes = Files.newInputStream(path).use { it.readNBytes(maxBytes + 1) }
    if (bytes.size > maxBytes) throw IOException("Vector XML exceeds the 1 MiB preview limit")
    val factory = DocumentBuilderFactory.newDefaultInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", 64)
        isXIncludeAware = false
    }
    val builder = factory.newDocumentBuilder()
    builder.setErrorHandler(object : DefaultHandler() {
        override fun error(exception: SAXParseException): Nothing = throw exception

        override fun fatalError(exception: SAXParseException): Nothing = throw exception
    })
    val document = try {
        builder.parse(ByteArrayInputStream(bytes))
    } catch (exception: SAXException) {
        throw IOException("Unable to parse vector XML: ${exception.message}", exception)
    }
    val root = document.documentElement
    if (root.tagName != "vector") throw IOException("Only <vector> drawable XML can be previewed. Open source to inspect this file.")
    val elements = document.getElementsByTagName("*")
    if (elements.length > 4096) throw IOException("Vector XML exceeds the 4096 element preview limit")
    for (index in 0 until elements.length) {
        val attributes = elements.item(index).attributes
        for (attribute in 0 until attributes.length) {
            val value = attributes.item(attribute).nodeValue.trim()
            if (value.startsWith('@') || value.startsWith('?')) throw IOException("Resource and theme references cannot be resolved in vector previews. Use literal values or open source.")
        }
    }
    val namespace = "http://schemas.android.com/apk/res/android"
    val width = root.getAttributeNS(namespace, "width")
    val height = root.getAttributeNS(namespace, "height")
    val dimension = Regex("([0-9]+(?:\\.[0-9]+)?)(?:dp|dip|px)")
    for (value in listOf(width, height)) {
        val number = dimension.matchEntire(value)?.groupValues?.get(1)?.toFloatOrNull()
        if (number == null || !number.isFinite() || number <= 0) throw IOException("Vector width and height must be positive literal dp or px dimensions")
    }
    for (attribute in listOf("viewportWidth", "viewportHeight")) {
        val number = root.getAttributeNS(namespace, attribute).toFloatOrNull()
        if (number == null || !number.isFinite() || number <= 0) throw IOException("Vector viewport dimensions must be positive finite numbers")
    }
    val errors = StringBuilder()
    val image = try {
        VdPreview.getPreviewFromVectorDocument(VdPreview.TargetSize.createFromMaxDimension(size), document, errors)
    } catch (exception: RuntimeException) {
        throw IOException("Unable to render vector XML: ${exception.message}", exception)
    }
    if (image == null || errors.isNotEmpty()) {
        image?.flush()
        throw IOException(errors.toString().ifBlank { "Unable to render vector XML" })
    }
    return VectorPreview(image = image, dimensions = "$width × $height")
}

fun showVectorPreview(project: Project, path: Path) {
    object : DialogWrapper(project, false) {
        private val panel = JPanel(BorderLayout())
        private val preview = CheckerboardPreview()
        private val dimensions = JLabel("Loading vector…", SwingConstants.CENTER)
        private var image: BufferedImage? = null
        private var closed = false
        private val worker = object : SwingWorker<VectorPreview, Void>() {
            override fun doInBackground(): VectorPreview = readVectorPreview(path = path, size = 512)

            override fun done() {
                if (closed) return
                try {
                    val result = get()
                    image = result.image
                    preview.icon = ImageIcon(result.image)
                    dimensions.text = result.dimensions
                } catch (_: CancellationException) {
                    return
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                } catch (exception: ExecutionException) {
                    dimensions.text = "Preview unavailable. Open source to inspect this file."
                    dimensions.toolTipText = exception.cause?.message
                }
                panel.revalidate()
                panel.repaint()
            }
        }

        init {
            title = path.name
            panel.preferredSize = JBUI.size(560, 560)
            preview.getAccessibleContext().accessibleName = "Vector drawable preview"
            panel.add(preview, BorderLayout.CENTER)
            val footer = JPanel(BorderLayout())
            footer.add(dimensions, BorderLayout.CENTER)
            footer.add(
                JButton("Open source").apply {
                    addActionListener {
                        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)?.let {
                            FileEditorManager.getInstance(project).openFile(it, true)
                        }
                    }
                },
                BorderLayout.EAST
            )
            panel.add(footer, BorderLayout.SOUTH)
            Disposer.register(disposable) {
                closed = true
                worker.cancel(true)
                preview.icon = null
                image?.flush()
            }
            setOKButtonText("Close")
            init()
            worker.execute()
        }

        override fun createCenterPanel(): JComponent = panel

        override fun createActions() = arrayOf(okAction)
    }.show()
}

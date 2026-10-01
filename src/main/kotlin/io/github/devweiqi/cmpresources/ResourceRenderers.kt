package io.github.devweiqi.cmpresources

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.nio.file.Path
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants
import kotlin.io.path.name

private val PREVIEW_LIGHT = JBColor(Color(0xF2F2F2), Color(0x383A3D))
private val PREVIEW_DARK = JBColor(Color(0xE4E4E4), Color(0x303235))

class ResourceGroupRenderer(
    private val thumbnail: (resource: Resource, size: Int) -> Icon,
    private val heading: (index: Int, root: Path) -> String?
) : JPanel(BorderLayout()), ListCellRenderer<ResourceGroup> {
    private val header = JLabel()
    private val row = JPanel(BorderLayout(JBUI.scale(10), 0))
    private val preview = CheckerboardPreview()
    private val name = JLabel()
    private val metadata = JLabel()

    init {
        header.font = header.font.deriveFont(Font.BOLD)
        header.border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(10, 2))
        row.border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(7, 2))
        preview.preferredSize = JBUI.size(52, 48)
        name.font = name.font.deriveFont(Font.BOLD)
        name.putClientProperty("html.disable", true)
        metadata.font = JBUI.Fonts.smallFont()
        val text = JPanel()
        text.isOpaque = false
        text.layout = BoxLayout(text, BoxLayout.Y_AXIS)
        text.border = JBUI.Borders.emptyTop(4)
        text.add(name)
        metadata.border = JBUI.Borders.emptyTop(4)
        text.add(metadata)
        row.add(preview, BorderLayout.WEST)
        row.add(text, BorderLayout.CENTER)
        add(header, BorderLayout.NORTH)
        add(row, BorderLayout.CENTER)
    }

    override fun getListCellRendererComponent(list: JList<out ResourceGroup>, value: ResourceGroup, index: Int, selected: Boolean, focus: Boolean): Component {
        val title = heading(index, value.root)
        header.text = title.orEmpty()
        header.isVisible = title != null
        header.foreground = list.foreground
        background = list.background
        row.background = if (selected) list.selectionBackground else list.background
        name.text = value.name
        name.foreground = if (selected) list.selectionForeground else list.foreground
        val type = if (value.versions.any { it.animated }) "Animated WebP" else resourceTypeLabel(type = value.type)
        metadata.text = "$type | ${value.versions.size} ${if (value.versions.size == 1) "version" else "versions"}"
        metadata.foreground = if (selected) list.selectionForeground else JBColor.GRAY
        preview.icon = thumbnail(value.versions.first(), 44)
        toolTipText = value.root.toString()
        preferredSize = JBUI.size(250, 64 + if (title == null) 0 else 40)
        getAccessibleContext().accessibleName = "${value.name}, ${metadata.text}"
        return this
    }
}

class ResourceVersionRenderer(private val thumbnail: (resource: Resource, size: Int) -> Icon) : JPanel(BorderLayout()), ListCellRenderer<Resource> {
    private val card = JPanel(BorderLayout())
    private val preview = CheckerboardPreview()
    private val qualifier = JLabel()
    private val filename = JLabel()
    private val size = JLabel()

    init {
        border = JBUI.Borders.empty(8)
        preview.preferredSize = JBUI.size(160, 132)
        qualifier.font = qualifier.font.deriveFont(Font.BOLD)
        filename.font = JBUI.Fonts.smallFont()
        size.font = JBUI.Fonts.smallFont()
        val text = JPanel()
        text.isOpaque = false
        text.layout = BoxLayout(text, BoxLayout.Y_AXIS)
        text.border = JBUI.Borders.empty(6, 8, 8, 8)
        text.add(qualifier)
        text.add(filename)
        text.add(size)
        card.add(preview, BorderLayout.NORTH)
        card.add(text, BorderLayout.CENTER)
        add(card, BorderLayout.CENTER)
    }

    override fun getListCellRendererComponent(list: JList<out Resource>, value: Resource, index: Int, selected: Boolean, focus: Boolean): Component {
        background = list.background
        card.background = list.background
        card.border = JBUI.Borders.customLine(if (selected) JBUI.CurrentTheme.Focus.focusColor() else JBColor.border())
        preview.icon = thumbnail(value, 124)
        qualifier.text = value.qualifier
        qualifier.foreground = list.foreground
        filename.text = value.file.name
        filename.foreground = JBColor.GRAY
        size.text = resourceSizeLabel(bytes = value.size) + if (value.animated) " · Animated" else ""
        size.foreground = JBColor.GRAY
        toolTipText = "${value.relativePath} (${size.text})"
        getAccessibleContext().accessibleName = "${value.qualifier}, ${value.file.name}, ${size.text}"
        return this
    }
}

class ResourceValueRenderer : JPanel(BorderLayout(0, JBUI.scale(6))), ListCellRenderer<Resource> {
    private val heading = JLabel()
    private val content = JTextArea()

    init {
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(10, 12))
        heading.font = heading.font.deriveFont(Font.BOLD)
        heading.putClientProperty("html.disable", true)
        content.font = JBUI.Fonts.label()
        content.isEditable = false
        content.isOpaque = false
        content.lineWrap = true
        content.wrapStyleWord = true
        add(heading, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
    }

    override fun getListCellRendererComponent(list: JList<out Resource>, value: Resource, index: Int, selected: Boolean, focus: Boolean): Component {
        val entry = requireNotNull(value.value)
        background = if (selected) list.selectionBackground else list.background
        heading.foreground = if (selected) list.selectionForeground else list.foreground
        content.foreground = heading.foreground
        heading.text = "${value.qualifier}  ·  ${value.file.name}:${entry.line}"
        content.text = entry.text.take(4000).ifEmpty { "(empty)" } + if (entry.text.length > 4000) "\n… Open source to view the full value." else ""
        content.setSize(maxOf(JBUI.scale(160), list.width - JBUI.scale(40)), Short.MAX_VALUE.toInt())
        preferredSize = Dimension(JBUI.scale(250), heading.preferredSize.height + content.preferredSize.height + JBUI.scale(27))
        toolTipText = value.relativePath
        getAccessibleContext().accessibleName = "${value.name}, ${value.qualifier}, ${content.text}"
        return this
    }
}

class CheckerboardPreview : JLabel() {
    init {
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        minimumSize = Dimension(0, 0)
    }

    override fun paintComponent(graphics: Graphics) {
        val step = JBUI.scale(8)
        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                graphics.color = if ((x / step + y / step) % 2 == 0) PREVIEW_LIGHT else PREVIEW_DARK
                graphics.fillRect(x, y, step, step)
            }
        }
        val image = icon as? ImageIcon
        if (image != null && width > 0 && height > 0 && (image.iconWidth > width || image.iconHeight > height)) {
            val scale = minOf(width.toDouble() / image.iconWidth, height.toDouble() / image.iconHeight)
            val imageWidth = maxOf(1, (image.iconWidth * scale).toInt())
            val imageHeight = maxOf(1, (image.iconHeight * scale).toInt())
            val scaled = graphics.create() as Graphics2D
            try {
                scaled.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                scaled.drawImage(image.image, (width - imageWidth) / 2, (height - imageHeight) / 2, imageWidth, imageHeight, null)
            } finally {
                scaled.dispose()
            }
        } else {
            super.paintComponent(graphics)
        }
    }
}

fun resourceTypeLabel(type: String): String = when (type) {
    "drawable" -> "Drawable"
    "string" -> "String"
    "string-array" -> "String Array"
    "plurals" -> "Plurals"
    "font" -> "Font"
    else -> "Files"
}

fun resourceSizeLabel(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.2f kB", bytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024))
}

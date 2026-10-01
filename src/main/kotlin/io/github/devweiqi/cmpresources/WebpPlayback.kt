package io.github.devweiqi.cmpresources

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingWorker
import javax.swing.Timer
import kotlin.io.path.name
import kotlin.time.Duration.Companion.milliseconds

private val PLAYBACK_LOG = Logger.getInstance("CMP Resource Manager playback")

fun playWebp(project: Project, path: Path) {
    object : DialogWrapper(project, false) {
        private val panel = JPanel(BorderLayout())
        private var player: WebpPlaybackPanel? = null
        private var closed = false
        private val worker = object : SwingWorker<WebpAnimation, Void>() {
            override fun doInBackground(): WebpAnimation {
                readWebpAnimation(path = path, size = 512)?.let { return it }
                val image = (loadResourcePreview(path = path, size = 512) as? ImageIcon)?.image as? BufferedImage
                    ?: throw IOException("Unable to decode this WebP image")
                ImageIO.createImageInputStream(path.toFile()).use { input ->
                    val reader = ImageIO.getImageReaders(input).next()
                    try {
                        reader.input = input
                        return WebpAnimation(frames = listOf(AnimationFrame(image = image, duration = 100.milliseconds)), loopCount = 1, width = reader.getWidth(0), height = reader.getHeight(0))
                    } finally {
                        reader.dispose()
                    }
                }
            }

            override fun done() {
                if (closed) return
                try {
                    player = WebpPlaybackPanel(animation = get())
                    panel.removeAll()
                    panel.add(player, BorderLayout.CENTER)
                } catch (_: CancellationException) {
                    return
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                } catch (exception: ExecutionException) {
                    PLAYBACK_LOG.warn("Unable to play $path", exception.cause)
                    panel.removeAll()
                    panel.add(JLabel(exception.cause?.message ?: "Unable to decode this WebP image", SwingConstants.CENTER), BorderLayout.CENTER)
                }
                panel.revalidate()
                panel.repaint()
            }
        }

        init {
            title = path.name
            panel.preferredSize = JBUI.size(560, 560)
            panel.add(JLabel("Loading WebP…", SwingConstants.CENTER), BorderLayout.CENTER)
            Disposer.register(disposable) {
                closed = true
                worker.cancel(true)
                player?.dispose()
            }
            setOKButtonText("Close")
            init()
            worker.execute()
        }

        override fun createCenterPanel(): JComponent = panel

        override fun createActions() = arrayOf(okAction)
    }.show()
}

class WebpPlaybackPanel(private val animation: WebpAnimation) : JPanel(BorderLayout()), Disposable {
    private val preview = CheckerboardPreview()
    private val toggle = JButton("Pause")
    private val position = JLabel()
    private var frameIndex = 0
    private var completedLoops = 0
    private var finished = false
    private var disposed = false
    private val timer = Timer(0) { advance() }.apply { isRepeats = false }

    init {
        require(animation.frames.isNotEmpty())
        preview.preferredSize = JBUI.size(512, 512)
        add(preview, BorderLayout.CENTER)
        val controls = JPanel(FlowLayout(FlowLayout.CENTER))
        controls.add(toggle)
        val metrics = position.getFontMetrics(position.font)
        val counterWidth = (1..animation.frames.size).maxOf { metrics.stringWidth("$it / ${animation.frames.size}") }
        position.preferredSize = Dimension(counterWidth, metrics.height)
        position.horizontalAlignment = SwingConstants.RIGHT
        controls.add(position)
        val dimensions = JLabel("${animation.width} × ${animation.height} px", SwingConstants.CENTER)
        dimensions.toolTipText = "Original image dimensions"
        val footer = JPanel(BorderLayout())
        footer.add(dimensions, BorderLayout.NORTH)
        footer.add(controls, BorderLayout.SOUTH)
        add(footer, BorderLayout.SOUTH)
        toggle.addActionListener {
            if (timer.isRunning) {
                timer.stop()
                toggle.text = "Play"
            } else if (!disposed) {
                if (finished) {
                    frameIndex = 0
                    completedLoops = 0
                    finished = false
                }
                showFrame()
                scheduleFrame()
                toggle.text = "Pause"
            }
        }
        showFrame()
        if (animation.frames.size > 1) {
            scheduleFrame()
        } else {
            toggle.isEnabled = false
            toggle.text = "Static image"
        }
    }

    private fun showFrame() {
        preview.icon = ImageIcon(animation.frames[frameIndex].image)
        preview.getAccessibleContext().accessibleName = "WebP frame ${frameIndex + 1} of ${animation.frames.size}"
        position.text = "${frameIndex + 1} / ${animation.frames.size}"
    }

    private fun scheduleFrame() {
        timer.initialDelay = animation.frames[frameIndex].duration.inWholeMilliseconds.coerceIn(10, Int.MAX_VALUE.toLong()).toInt()
        timer.restart()
    }

    private fun advance() {
        if (disposed) return
        if (frameIndex == animation.frames.lastIndex) {
            completedLoops++
            if (animation.loopCount > 0 && completedLoops >= animation.loopCount) {
                finished = true
                toggle.text = "Play"
                return
            }
            frameIndex = 0
        } else {
            frameIndex++
        }
        showFrame()
        scheduleFrame()
    }

    override fun dispose() {
        disposed = true
        timer.stop()
        toggle.isEnabled = false
        preview.icon = null
        animation.frames.forEach { it.image.flush() }
    }
}

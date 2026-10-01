package io.github.devweiqi.cmpresources

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.ImageIcon
import kotlin.io.path.extension

fun loadResourcePreview(path: Path, size: Int): Icon? {
    if (Files.size(path) > 32L * 1024 * 1024) return null
    if (path.extension.equals(other = "xml", ignoreCase = true)) {
        return ImageIcon(readVectorPreview(path = path, size = size).image)
    }
    if (path.extension.equals(other = "webp", ignoreCase = true)) {
        readWebpAnimation(path = path, size = size, firstFrameOnly = true)?.let { return ImageIcon(it.frames.first().image) }
    }
    ImageIO.createImageInputStream(path.toFile())?.use { input ->
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width <= 0 || height <= 0 || width.toLong() * height > 32_000_000) return null
            val parameters = reader.defaultReadParam
            val sample = maxOf(1, maxOf(width, height) / size)
            parameters.setSourceSubsampling(sample, sample, 0, 0)
            val source = reader.read(0, parameters)
            try {
                return ImageIcon(scaledResourceImage(source = source, size = size))
            } finally {
                source.flush()
            }
        } finally {
            reader.dispose()
        }
    }
    return null
}

fun scaledResourceImage(source: BufferedImage, size: Int): BufferedImage {
    val scale = size.toDouble() / maxOf(source.width, source.height)
    val target = BufferedImage(maxOf(1, (source.width * scale).toInt()), maxOf(1, (source.height * scale).toInt()), BufferedImage.TYPE_INT_ARGB)
    val graphics = target.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(source, 0, 0, target.width, target.height, null)
    } finally {
        graphics.dispose()
    }
    return target
}

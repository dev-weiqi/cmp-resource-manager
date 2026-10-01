package io.github.devweiqi.cmpresources

import java.awt.AlphaComposite
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class AnimationFrame(val image: BufferedImage, val duration: Duration)

data class WebpAnimation(val frames: List<AnimationFrame>, val loopCount: Int, val width: Int, val height: Int)

private const val MAX_WEBP_BYTES = 32 * 1024 * 1024
private const val MAX_CANVAS_PIXELS = 32_000_000
private const val MAX_ANIMATION_BYTES = 128L * 1024 * 1024

fun readWebpAnimation(path: Path, size: Int, firstFrameOnly: Boolean = false): WebpAnimation? {
    if (Files.size(path) > MAX_WEBP_BYTES) throw IOException("WebP exceeds the 32 MiB preview limit")
    val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_WEBP_BYTES + 1) }
    if (bytes.size > MAX_WEBP_BYTES) throw IOException("WebP exceeds the 32 MiB preview limit")
    return decodeWebpAnimation(bytes = bytes, size = size, firstFrameOnly = firstFrameOnly)
}

fun decodeWebpAnimation(bytes: ByteArray, size: Int, firstFrameOnly: Boolean = false): WebpAnimation? {
    require(size in 1..1024)
    if (bytes.size < 12 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WEBP") return null
    if (bytes.size > MAX_WEBP_BYTES || bytes.uint(offset = 4, count = 4) + 8 != bytes.size.toLong()) throw IOException("Invalid WebP container length")
    var canvas: BufferedImage? = null
    var loops = 0
    var hasAnimationHeader = false
    val frames = mutableListOf<AnimationFrame>()
    var offset = 12
    while (offset < bytes.size) {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
        if (offset + 8 > bytes.size) throw IOException("Truncated WebP chunk")
        val tag = String(bytes, offset, 4, Charsets.US_ASCII)
        val length = bytes.uint(offset = offset + 4, count = 4)
        val start = offset + 8
        val end = start.toLong() + length
        if (end + length % 2 > bytes.size) throw IOException("Truncated WebP chunk: $tag")
        when (tag) {
            "VP8X" -> {
                if (length != 10L || canvas != null) throw IOException("Invalid WebP canvas")
                if (bytes[start].toInt() and 2 == 0) return null
                val width = bytes.uint(offset = start + 4, count = 3).toInt() + 1
                val height = bytes.uint(offset = start + 7, count = 3).toInt() + 1
                if (width.toLong() * height > MAX_CANVAS_PIXELS) throw IOException("WebP canvas exceeds 32 million pixels")
                canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            }

            "ANIM" -> {
                if (canvas == null) throw IOException("Missing WebP canvas")
                if (length != 6L || hasAnimationHeader || frames.isNotEmpty()) throw IOException("Invalid WebP animation header")
                loops = bytes.uint(offset = start + 4, count = 2).toInt()
                hasAnimationHeader = true
            }

            "ANMF" -> {
                val target = canvas ?: throw IOException("Missing WebP canvas")
                if (!hasAnimationHeader || length < 24) throw IOException("Invalid WebP animation frame")
                val x = bytes.uint(offset = start, count = 3).toInt() * 2
                val y = bytes.uint(offset = start + 3, count = 3).toInt() * 2
                val width = bytes.uint(offset = start + 6, count = 3).toInt() + 1
                val height = bytes.uint(offset = start + 9, count = 3).toInt() + 1
                if (x.toLong() + width > target.width || y.toLong() + height > target.height) throw IOException("WebP frame is outside its canvas")
                val previewScale = size.toDouble() / maxOf(target.width, target.height)
                val previewWidth = maxOf(1, (target.width * previewScale).toInt())
                val previewHeight = maxOf(1, (target.height * previewScale).toInt())
                if ((frames.size + 1L) * previewWidth * previewHeight * 4 > MAX_ANIMATION_BYTES || frames.size >= 1000) {
                    throw IOException("Animation exceeds the preview memory limit")
                }
                val payload = bytes.copyOfRange(fromIndex = start + 16, toIndex = end.toInt())
                val frameFile = ByteBuffer.allocate(30 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
                frameFile.put("RIFF".toByteArray()).putInt(22 + payload.size).put("WEBPVP8X".toByteArray()).putInt(10)
                frameFile.put(byteArrayOf(16, 0, 0, 0)).put(bytes, start + 6, 6).put(payload)
                val decoded = decodeFrame(bytes = frameFile.array(), width = width, height = height)
                try {
                    if (decoded.width != width || decoded.height != height) throw IOException("WebP frame dimensions do not match")
                    val flags = bytes[start + 15].toInt()
                    val graphics = target.createGraphics()
                    try {
                        // ponytail: Java2D sRGB compositing; use color-managed blending if exact ICC reproduction is needed.
                        graphics.composite = if (flags and 2 != 0) AlphaComposite.Src else AlphaComposite.SrcOver
                        graphics.drawImage(decoded, x, y, null)
                    } finally {
                        graphics.dispose()
                    }
                    frames += AnimationFrame(
                        image = scaledResourceImage(source = target, size = size),
                        duration = bytes.uint(offset = start + 12, count = 3).coerceAtLeast(minimumValue = 10).milliseconds
                    )
                    if (firstFrameOnly) return WebpAnimation(frames = frames, loopCount = loops, width = target.width, height = target.height)
                    // ANIM background is a viewer hint; keep disposal transparent, matching libwebp previews.
                    if (flags and 1 != 0) target.clearRect(x = x, y = y, width = width, height = height)
                } finally {
                    decoded.flush()
                }
            }
        }
        offset = (end + length % 2).toInt()
    }
    if (canvas == null) return null
    if (frames.isEmpty()) throw IOException("WebP animation has no frames")
    return WebpAnimation(frames = frames, loopCount = loops, width = canvas.width, height = canvas.height)
}

private fun ByteArray.uint(offset: Int, count: Int): Long {
    if (offset < 0 || offset + count > size) throw IOException("Truncated WebP header")
    var value = 0L
    repeat(count) { index -> value = value or ((this[offset + index].toLong() and 255) shl (index * 8)) }
    return value
}

private fun decodeFrame(bytes: ByteArray, width: Int, height: Int): BufferedImage {
    ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) throw IOException("WebP decoder is unavailable")
        val reader = readers.next()
        try {
            reader.input = input
            if (reader.getWidth(0) != width || reader.getHeight(0) != height) throw IOException("WebP frame dimensions do not match")
            return reader.read(0)
        } finally {
            reader.dispose()
        }
    }
}

private fun BufferedImage.clearRect(x: Int, y: Int, width: Int, height: Int) {
    val graphics = createGraphics()
    try {
        graphics.composite = AlphaComposite.Clear
        graphics.fillRect(x, y, width, height)
    } finally {
        graphics.dispose()
    }
}

package io.github.devweiqi.cmpresources

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.Locator
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

data class ResourceValue(val type: String, val key: String, val text: String, val line: Int)

fun readResourceValues(path: Path): List<ResourceValue> {
    val maxBytes = 8 * 1024 * 1024
    if (Files.size(path) > maxBytes) throw IOException("Values XML exceeds the 8 MiB preview limit")
    val bytes = Files.newInputStream(path).use { it.readNBytes(maxBytes + 1) }
    if (bytes.size > maxBytes) throw IOException("Values XML exceeds the 8 MiB preview limit")
    val factory = SAXParserFactory.newDefaultInstance()
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    val parser = factory.newSAXParser()
    parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    val resources = mutableListOf<ResourceValue>()
    val handler = object : DefaultHandler() {
        private var locator: Locator? = null
        private var depth = 0
        private var type: String? = null
        private var key = ""
        private var line = 1
        private var itemLabel: String? = null
        private val text = StringBuilder()
        private val items = mutableListOf<String>()

        override fun setDocumentLocator(value: Locator) {
            locator = value
        }

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            depth++
            if (depth == 1 && qName != "resources") throw SAXException("Expected a resources root element")
            if (depth == 2) {
                type = when (qName) {
                    "string", "string-array", "plurals" -> qName
                    else -> null
                }
                if (type != null) {
                    key = attributes.getValue("name") ?: throw SAXException("Missing resource name")
                    if (key.isBlank()) throw SAXException("Empty resource name")
                    line = locator?.lineNumber ?: 1
                    text.setLength(0)
                    items.clear()
                }
            } else if (depth == 3 && type != null && type != "string" && qName == "item") {
                itemLabel = if (type == "plurals") attributes.getValue("quantity") ?: throw SAXException("Missing plural quantity") else "[${items.size}]"
                text.setLength(0)
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (type == "string" || itemLabel != null) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (depth == 3 && qName == "item" && itemLabel != null) {
                items += "$itemLabel: $text"
                itemLabel = null
            } else if (depth == 2) {
                type?.let {
                    resources += ResourceValue(type = it, key = key, text = if (it == "string") text.toString() else items.joinToString(separator = "\n"), line = line)
                }
                type = null
            }
            depth--
        }

        override fun error(exception: SAXParseException): Nothing = throw exception

        override fun fatalError(exception: SAXParseException): Nothing = throw exception
    }
    try {
        parser.parse(ByteArrayInputStream(bytes), handler)
    } catch (exception: SAXException) {
        throw IOException("Unable to parse values XML: ${exception.message}", exception)
    }
    return resources
}

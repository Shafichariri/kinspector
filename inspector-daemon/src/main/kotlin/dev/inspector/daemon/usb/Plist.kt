package dev.inspector.daemon.usb

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Just enough XML property list to talk to usbmuxd, and no more.
 *
 * usbmuxd speaks XML plists in both directions. Writing one is a template; reading one needs a
 * parser, and the JDK's DOM parser is already here, so this costs no dependency. It reads every
 * value type usbmuxd was seen to send — a real `ListDevices` reply carries a multi-line `<data>`
 * block — and writes only what a request needs: strings and integers.
 */
internal object Plist {

    /** A request: a flat dict of strings and integers. */
    fun encode(dict: Map<String, Any>): ByteArray = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">""")
        append('\n').append("""<plist version="1.0">""").append('\n').append("<dict>\n")
        for ((key, value) in dict) {
            append("\t<key>").append(escape(key)).append("</key>\n")
            when (value) {
                is String -> append("\t<string>").append(escape(value)).append("</string>\n")
                is Int, is Long -> append("\t<integer>").append(value).append("</integer>\n")
                is Boolean -> append(if (value) "\t<true/>\n" else "\t<false/>\n")
                else -> throw IllegalArgumentException("cannot encode ${value::class.simpleName} for '$key'")
            }
        }
        append("</dict>\n</plist>\n")
    }.encodeToByteArray()

    /**
     * The top-level value: a `Map<String, Any?>` for a dict, `List<Any?>` for an array, `Long` for
     * an integer, `ByteArray` for data.
     */
    fun decode(bytes: ByteArray): Any? {
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val root = document.documentElement
        require(root.tagName == "plist") { "not a plist: <${root.tagName}>" }
        return root.elements().firstOrNull()?.let(::value)
    }

    private fun value(element: Element): Any? = when (element.tagName) {
        "dict" -> {
            val children = element.elements()
            require(children.size % 2 == 0) { "dict with an unpaired key" }
            children.chunked(2).associate { (key, value) ->
                require(key.tagName == "key") { "expected <key>, got <${key.tagName}>" }
                key.textContent to value(value)
            }
        }
        "array" -> element.elements().map(::value)
        "string", "date" -> element.textContent
        "integer" -> element.textContent.trim().toLong()
        "real" -> element.textContent.trim().toDouble()
        "true" -> true
        "false" -> false
        // Apple wraps long data across lines; the MIME decoder ignores the whitespace.
        "data" -> Base64.getMimeDecoder().decode(element.textContent.trim())
        else -> throw IllegalArgumentException("unsupported plist element <${element.tagName}>")
    }

    private fun Element.elements(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }
            .filter { it.nodeType == Node.ELEMENT_NODE }
            .map { it as Element }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * Every reply names Apple's DTD by URL. A default parser **fetches it** — a network request
     * per message, to apple.com, from a daemon whose whole posture is loopback-only — and a parser
     * that expands entities is the usual XXE hole besides. Neither is wanted; the DTD adds nothing.
     */
    private val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isExpandEntityReferences = false
        isXIncludeAware = false
        isNamespaceAware = false
    }
}

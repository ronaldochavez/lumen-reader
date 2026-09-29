package com.folio.reader.data.epub

import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File

data class ParsedEpub(
    val title: String,
    val author: String,
    val language: String?,
    /** Paths are relative to the extracted book root. */
    val coverHref: String?,
    val spine: List<SpineItem>,
    val toc: List<TocItem>,
)

data class SpineItem(val href: String, val mediaType: String)

data class TocItem(val title: String, val href: String, val fragment: String?, val depth: Int)

/**
 * Minimal EPUB 2/3 parser working over an already-extracted book directory:
 * container.xml -> OPF (metadata, manifest, spine) -> nav.xhtml or toc.ncx.
 */
class EpubParser(private val root: File) {

    private data class ManifestItem(val id: String, val href: String, val mediaType: String, val properties: String)

    fun parse(): ParsedEpub {
        val container = File(root, "META-INF/container.xml")
        require(container.exists()) { "EPUB inválido: falta META-INF/container.xml" }
        checkDrm()
        val containerDoc = xml(container)
        val opfPath = containerDoc.allElements.firstOrNull { it.localName() == "rootfile" }
            ?.attr("full-path")
            ?.takeIf { it.isNotBlank() }
            ?: error("EPUB inválido: no se encontró el archivo OPF")

        val opfFile = File(root, opfPath)
        require(opfFile.exists()) { "EPUB inválido: falta $opfPath" }
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = xml(opfFile)

        fun meta(name: String): String? = opf.allElements
            .firstOrNull { it.localName() == name && it.text().isNotBlank() }
            ?.text()?.trim()

        val manifest = opf.allElements
            .filter { it.localName() == "item" }
            .mapNotNull { el ->
                val id = el.attr("id")
                val href = el.attr("href")
                if (id.isBlank() || href.isBlank()) null
                else ManifestItem(id, resolve(opfDir, href), el.attr("media-type"), el.attr("properties"))
            }
            .associateBy { it.id }

        val spineEl = opf.allElements.firstOrNull { it.localName() == "spine" }
        val spine = spineEl?.children()
            ?.filter { it.localName() == "itemref" }
            ?.mapNotNull { manifest[it.attr("idref")] }
            ?.filter { it.mediaType.contains("html") || it.href.endsWith("html", ignoreCase = true) }
            ?.map { SpineItem(it.href, it.mediaType) }
            .orEmpty()
        require(spine.isNotEmpty()) { "EPUB sin contenido legible" }

        val cover = manifest.values.firstOrNull { "cover-image" in it.properties.split(' ') }
            ?: opf.allElements
                .firstOrNull { it.localName() == "meta" && it.attr("name") == "cover" }
                ?.attr("content")?.let { manifest[it] }
            ?: manifest.values.firstOrNull {
                it.mediaType.startsWith("image/") &&
                    (it.id.contains("cover", true) || it.href.contains("cover", true))
            }

        val navItem = manifest.values.firstOrNull { "nav" in it.properties.split(' ') }
        val ncxItem = spineEl?.attr("toc")?.let { manifest[it] }
            ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }

        val toc = runCatching {
            navItem?.let { parseNav(it.href) }?.takeIf { it.isNotEmpty() }
                ?: ncxItem?.let { parseNcx(it.href) }
        }.getOrNull().orEmpty()

        return ParsedEpub(
            title = meta("title") ?: root.name,
            author = meta("creator") ?: "Autor desconocido",
            language = meta("language"),
            coverHref = cover?.href?.takeIf { File(root, it).exists() },
            spine = spine,
            toc = toc,
        )
    }

    /** Adobe/Apple/Readium LCP DRM encrypts the content files; only font obfuscation is harmless. */
    private fun checkDrm() {
        val drm = "Este EPUB tiene DRM (protección anticopia) y no se puede abrir. Solo se admiten libros sin DRM."
        if (File(root, "META-INF/rights.xml").exists() || File(root, "META-INF/license.lcpl").exists()) {
            throw com.folio.reader.data.convert.UnsupportedBookException(drm)
        }
        val enc = File(root, "META-INF/encryption.xml")
        if (enc.exists()) {
            val fontObfuscation = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")
            val algorithms = xml(enc).allElements.filter { it.localName() == "EncryptionMethod" }.map { it.attr("Algorithm") }
            if (algorithms.any { it !in fontObfuscation }) throw com.folio.reader.data.convert.UnsupportedBookException(drm)
        }
    }

    private fun parseNav(href: String): List<TocItem> {
        val file = File(root, href)
        if (!file.exists()) return emptyList()
        val doc = Jsoup.parse(file, "UTF-8")
        val navs = doc.getElementsByTag("nav")
        val tocNav = navs.firstOrNull { it.attr("epub:type").split(' ').contains("toc") } ?: navs.firstOrNull()
            ?: return emptyList()
        val baseDir = href.substringBeforeLast('/', "")
        val out = mutableListOf<TocItem>()
        fun walk(ol: Element, depth: Int) {
            for (li in ol.children().filter { it.tagName() == "li" }) {
                val a = li.children().firstOrNull { it.tagName() == "a" }
                    ?: li.children().firstOrNull { it.tagName() == "span" }?.selectFirst("a")
                if (a != null && a.attr("href").isNotBlank()) {
                    val (path, fragment) = splitHref(a.attr("href"))
                    out += TocItem(a.text().trim(), resolve(baseDir, path), fragment, depth)
                }
                li.children().filter { it.tagName() == "ol" }.forEach { walk(it, depth + 1) }
            }
        }
        tocNav.children().filter { it.tagName() == "ol" }.forEach { walk(it, 0) }
        return out
    }

    private fun parseNcx(href: String): List<TocItem> {
        val file = File(root, href)
        if (!file.exists()) return emptyList()
        val doc = xml(file)
        val baseDir = href.substringBeforeLast('/', "")
        val out = mutableListOf<TocItem>()
        fun walk(parent: Element, depth: Int) {
            for (np in parent.children().filter { it.localName() == "navPoint" }) {
                val label = np.children().firstOrNull { it.localName() == "navLabel" }?.text()?.trim().orEmpty()
                val src = np.children().firstOrNull { it.localName() == "content" }?.attr("src").orEmpty()
                if (src.isNotBlank()) {
                    val (path, fragment) = splitHref(src)
                    out += TocItem(label, resolve(baseDir, path), fragment, depth)
                }
                walk(np, depth + 1)
            }
        }
        doc.allElements.firstOrNull { it.localName() == "navMap" }?.let { walk(it, 0) }
        return out
    }

    private fun xml(file: File): Document = Jsoup.parse(file.readText(), "", Parser.xmlParser())

    private fun Element.localName(): String = tagName().substringAfter(':')

    companion object {
        fun splitHref(href: String): Pair<String, String?> {
            val i = href.indexOf('#')
            return if (i < 0) href to null else href.substring(0, i) to href.substring(i + 1).ifBlank { null }
        }

        /** Resolves [href] (URL-encoded, relative) against [baseDir] into a normalized root-relative path. */
        fun resolve(baseDir: String, href: String): String {
            val decoded = Uri.decode(href)
            val joined = if (baseDir.isEmpty() || decoded.startsWith("/")) decoded.trimStart('/') else "$baseDir/$decoded"
            val parts = ArrayDeque<String>()
            for (seg in joined.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> parts.removeLastOrNull()
                    else -> parts.addLast(seg)
                }
            }
            return parts.joinToString("/")
        }
    }
}

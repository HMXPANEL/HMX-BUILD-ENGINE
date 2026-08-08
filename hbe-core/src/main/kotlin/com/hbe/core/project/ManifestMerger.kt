package com.hbe.core.project

import com.hbe.api.FileSystem
import com.hbe.api.Logger
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Merges an Android application's own manifest with the manifests contributed
 * by its AAR library dependencies, producing the single manifest that gets
 * packaged into the APK.
 *
 * Android libraries declare components (providers, receivers, services), permissions,
 * and application-level attributes in their own AndroidManifest.xml. AGP's manifest
 * merger combines these into the final manifest. HMX reproduces the essential parts
 * of that merge so AndroidX and other libraries work correctly.
 *
 * Merge semantics (subset of AGP's rules, sufficient for AndroidX):
 *   - The app manifest is the "main" manifest.
 *   - Library manifest <application> children (activities, services, providers,
 *     receivers, meta-data) are merged into the app's <application> element.
 *   - Components with the same android:name are deduplicated (first wins, so the
 *     app's own declaration takes precedence over a library's).
 *   - Library <uses-permission> entries are added if not already present.
 *   - The app's application-level attributes (e.g. android:appComponentFactory) are
 *     preserved; library values only fill in attributes the app did not set.
 *   - ${applicationId} placeholders in library manifests are replaced with the
 *     actual application id (used e.g. by androidx.startup's authority).
 *   - All namespace declarations from every manifest are collected onto the root.
 */
class ManifestMerger(
    private val fileSystem: FileSystem,
    private val logger: Logger
) {

    /**
     * Merge [appManifest] with [libraryManifests], substituting [applicationId]
     * for ${applicationId} placeholders. Writes the result to a temp file and
     * returns its path.
     */
    fun merge(
        appManifest: Path,
        libraryManifests: List<Path>,
        applicationId: String
    ): Path {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setNamespaceAware(true)
        val builder = factory.newDocumentBuilder()

        val appDoc = builder.parse(appManifest.toFile())
        val appRoot = appDoc.documentElement
        val appApplication = findChild(appRoot, "application")
            ?: error("App manifest has no <application> element: $appManifest")

        // Collect every namespace URI declared anywhere so the merged root has them.
        val allNamespaces = LinkedHashMap<String, String>()  // uri → prefix
        collectNamespaces(appRoot, allNamespaces)
        for (libManifest in libraryManifests) {
            if (!fileSystem.exists(libManifest)) continue
            val libDoc = builder.parse(libManifest.toFile())
            val libRoot = libDoc.documentElement
            collectNamespaces(libRoot, allNamespaces)
            val libApplication = findChild(libRoot, "application")
            if (libApplication != null) {
                mergeApplication(appApplication, libApplication, applicationId)
            }
            mergePermissions(appRoot, libRoot, applicationId)
            mergePermissionDeclarations(appRoot, libRoot, applicationId)
        }

        // Apply collected namespace declarations to the merged root.
        for ((uri, prefix) in allNamespaces) {
            val attrName = if (prefix.isEmpty()) "xmlns" else "xmlns:$prefix"
            if (appRoot.getAttributeNode(attrName) == null) {
                appRoot.setAttributeNS(NS_XMLNS, attrName, uri)
            }
        }

        val merged = fileSystem.createTempFile("hbe-manifest-merged-", ".xml")
        writeDocument(appDoc, merged)
        return merged
    }

    // ---- application element merge ----------------------------------------

    private fun mergeApplication(
        appApp: Element,
        libApp: Element,
        applicationId: String
    ) {
        // 1. Application-level attributes: library fills only what app didn't set.
        for (attr in attributesOf(libApp)) {
            if (attr.namespaceURI != ANDROID_NS) continue
            val localName = attr.localName
            if (appApp.hasAttributeNS(ANDROID_NS, localName)) continue
            val value = substitutePlaceholders(attr.nodeValue, applicationId)
            appApp.setAttributeNS(ANDROID_NS, "android:$localName", value)
        }

        // 2. Child elements (activities, services, providers, receivers, meta-data).
        val seen = mutableSetOf<String>()
        for (child in childElements(appApp)) {
            componentKey(child)?.let { seen.add(it) }
        }

        for (libChild in childElements(libApp)) {
            val key = componentKey(libChild) ?: continue
            if (seen.contains(key)) continue
            seen += key
            val imported = appApp.ownerDocument.importNode(libChild, true)
            substituteInPlace(imported, applicationId)
            appApp.appendChild(imported)
        }
    }

    /** Build a dedup key for a component element based on its type and name. */
    private fun componentKey(element: Element): String? {
        val nsName = element.getAttributeNS(ANDROID_NS, "name")
        val rawName = element.getAttribute("name")
        val name = nsName.takeIf { it.isNotBlank() } ?: rawName.takeIf { it.isNotBlank() } ?: return null
        return "${element.tagName}/$name"
    }

    // ---- permissions merge -----------------------------------------------

    private fun mergePermissions(appRoot: Element, libRoot: Element, applicationId: String) {
        val existing = mutableSetOf<String>()
        for (perm in childElements(appRoot).filter { it.tagName == "uses-permission" }) {
            perm.getAttributeNS(ANDROID_NS, "name").takeIf { it.isNotBlank() }?.let { existing += it }
        }
        for (libPerm in childElements(libRoot).filter { it.tagName == "uses-permission" }) {
            val rawName = libPerm.getAttributeNS(ANDROID_NS, "name").takeIf { it.isNotBlank() } ?: continue
            val name = substitutePlaceholders(rawName, applicationId)
            if (existing.contains(name)) continue
            existing += name
            val imported = appRoot.ownerDocument.importNode(libPerm, true) as Element
            imported.setAttributeNS(ANDROID_NS, "android:name", name)
            appRoot.insertBefore(imported, appRoot.firstChild)
        }
    }

    // ---- placeholder substitution ----------------------------------------

    private fun substituteInPlace(node: Node, applicationId: String) {
        if (node.nodeType == Node.ELEMENT_NODE) {
            val el = node as Element
            for (attr in attributesOf(el)) {
                val v = attr.nodeValue
                if (v.contains("\${applicationId}")) {
                    attr.nodeValue = v.replace("\${applicationId}", applicationId)
                }
            }
        }
        for (child in childElements(node)) {
            substituteInPlace(child, applicationId)
        }
    }

    private fun substitutePlaceholders(value: String, applicationId: String): String {
        return value.replace("\${applicationId}", applicationId)
    }

    /**
     * Merges top-level <permission> declarations (not uses-permission) from the
     * library into the app manifest, substituting ${applicationId} placeholders.
     */
    private fun mergePermissionDeclarations(appRoot: Element, libRoot: Element, applicationId: String) {
        val existing = mutableSetOf<String>()
        for (perm in childElements(appRoot).filter { it.tagName == "permission" }) {
            perm.getAttributeNS(ANDROID_NS, "name").takeIf { it.isNotBlank() }?.let { existing += it }
        }
        for (libPerm in childElements(libRoot).filter { it.tagName == "permission" }) {
            val rawName = libPerm.getAttributeNS(ANDROID_NS, "name").takeIf { it.isNotBlank() } ?: continue
            val name = substitutePlaceholders(rawName, applicationId)
            if (existing.contains(name)) continue
            existing += name
            val imported = appRoot.ownerDocument.importNode(libPerm, true)
            substituteInPlace(imported, applicationId)
            appRoot.insertBefore(imported, appRoot.firstChild)
        }
    }

    // ---- helpers ----------------------------------------------------------

    private fun collectNamespaces(root: Element, target: MutableMap<String, String>) {
        for (attr in attributesOf(root)) {
            val name = attr.nodeName
            val value = attr.nodeValue ?: continue
            if (name.startsWith("xmlns:")) {
                if (!target.containsKey(value)) {
                    target[value] = name.removePrefix("xmlns:")
                }
            } else if (name == "xmlns") {
                if (!target.containsKey(value)) {
                    target[value] = ""
                }
            }
        }
    }

    private fun findChild(parent: Element, tag: String): Element? {
        for (child in childElements(parent)) {
            if (child.tagName == tag) return child
        }
        return null
    }

    private fun childElements(node: Node): List<Element> {
        val result = mutableListOf<Element>()
        val children = node.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n.nodeType == Node.ELEMENT_NODE) result += n as Element
        }
        return result
    }

    private fun attributesOf(element: Element): List<org.w3c.dom.Attr> {
        val result = mutableListOf<org.w3c.dom.Attr>()
        val attrs = element.attributes
        for (i in 0 until attrs.length) {
            result += attrs.item(i) as org.w3c.dom.Attr
        }
        return result
    }

    private fun writeDocument(doc: Document, target: Path) {
        fileSystem.createDirectories(target.parent)
        val transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes")
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "utf-8")
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "no")
        val out = java.io.ByteArrayOutputStream()
        transformer.transform(javax.xml.transform.dom.DOMSource(doc), javax.xml.transform.stream.StreamResult(out))
        fileSystem.writeBytes(target, out.toByteArray())
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val NS_XMLNS = "http://www.w3.org/2000/xmlns/"
    }
}

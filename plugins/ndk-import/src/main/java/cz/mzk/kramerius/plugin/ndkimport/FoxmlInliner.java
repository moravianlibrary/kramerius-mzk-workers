// Rewrites managed file references before ingest as required by README.md → ndk-import → How a run works steps 3–4.
package cz.mzk.kramerius.plugin.ndkimport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public final class FoxmlInliner {
    private static final String FOXML_NAMESPACE = "info:fedora/fedora-system:def/foxml#";

    public InlinePlan plan(Path batchDirectory, NdkImportConfiguration configuration)
            throws IOException {
        List<Path> objects = listObjects(batchDirectory);
        List<InlineEntry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Path> missing = new ArrayList<>();

        for (Path object : objects) {
            PlannedReferences references =
                    entriesFor(object, parse(object), batchDirectory, configuration);
            entries.addAll(references.entries());
            warnings.addAll(references.warnings());
            missing.addAll(references.missing());
        }

        if (!missing.isEmpty()) {
            throw new MissingSourceFilesException(missing);
        }

        return new InlinePlan(List.copyOf(objects), List.copyOf(entries), List.copyOf(warnings));
    }

    private static List<Path> listObjects(Path batchDirectory) throws IOException {
        try (var paths = Files.list(batchDirectory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .toList();
        }
    }

    private static PlannedReferences entriesFor(
            Path object,
            Document document,
            Path batchDirectory,
            NdkImportConfiguration configuration)
            throws IOException {
        List<InlineEntry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Path> missing = new ArrayList<>();
        String pid = document.getDocumentElement().getAttribute("PID");
        NodeList datastreams = document.getElementsByTagNameNS(FOXML_NAMESPACE, "datastream");

        for (int index = 0; index < datastreams.getLength(); index++) {
            Element datastream = (Element) datastreams.item(index);
            String dsid = datastream.getAttribute("ID");
            NodeList versions =
                    datastream.getElementsByTagNameNS(FOXML_NAMESPACE, "datastreamVersion");
            for (int versionIndex = 0; versionIndex < versions.getLength(); versionIndex++) {
                Element version = (Element) versions.item(versionIndex);
                Element location = firstChild(version, "contentLocation");
                if (location == null) {
                    continue;
                }
                String reference = location.getAttribute("REF");
                if (!isFileReference(reference)) {
                    continue;
                }
                // Other DSIDs are left alone, so only internalized ones are resolved and confined.
                if (!configuration.internalizedDatastreams().contains(dsid)) {
                    warnings.add("File reference in " + pid + " " + dsid + ": " + reference);
                    continue;
                }
                Path source = resolveReference(reference, batchDirectory, configuration, pid, dsid);
                if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
                    missing.add(source);
                    continue;
                }
                entries.add(
                        new InlineEntry(
                                object,
                                pid,
                                object.getFileName().toString(),
                                dsid,
                                version.getAttribute("ID"),
                                version.getAttribute("MIMETYPE"),
                                source));
            }
        }

        return new PlannedReferences(entries, warnings, missing);
    }

    private static Path resolveReference(
            String reference,
            Path batchDirectory,
            NdkImportConfiguration configuration,
            String pid,
            String dsid)
            throws IOException {
        Path input;
        try {
            if (reference.startsWith("file:")) {
                input = Path.of(URI.create(reference));
            } else {
                input = Path.of(reference);
                if (!input.isAbsolute()) {
                    input = batchDirectory.resolve(input);
                }
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException(
                    "Invalid file reference for " + pid + " " + dsid + ": " + reference, exception);
        }

        Path mapped = configuration.mapPath(input).toAbsolutePath().normalize();
        Path checked = Files.exists(mapped) ? mapped.toRealPath() : mapped;
        if (!isUnderRoot(checked, configuration.root())
                && !isUnderRoot(checked, configuration.importDirectory())) {
            throw new IOException(
                    "File reference outside configured roots for "
                            + pid
                            + " "
                            + dsid
                            + ": "
                            + reference);
        }
        return checked;
    }

    private static boolean isFileReference(String reference) {
        return reference.startsWith("file:") || !hasScheme(reference);
    }

    private static boolean hasScheme(String reference) {
        try {
            URI uri = URI.create(reference);
            return uri.getScheme() != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isUnderRoot(Path candidate, Path root) throws IOException {
        if (root == null) {
            return false;
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path checkedRoot =
                Files.exists(normalizedRoot) ? normalizedRoot.toRealPath() : normalizedRoot;
        return candidate.startsWith(checkedRoot);
    }

    public void write(InlinePlan plan, Path targetDirectory) throws IOException {
        Files.createDirectories(targetDirectory);
        for (Path object : plan.objects()) {
            List<InlineEntry> rewrites =
                    plan.entries().stream().filter(entry -> entry.object().equals(object)).toList();
            Path target = targetDirectory.resolve(object.getFileName());
            if (rewrites.isEmpty()) {
                Files.copy(object, target);
                continue;
            }
            Document document = parse(object);
            for (InlineEntry entry : rewrites) {
                Element version = findVersion(document, entry.dsid(), entry.versionId());
                Element location = firstChild(version, "contentLocation");
                Element binary =
                        document.createElementNS(
                                FOXML_NAMESPACE, qualifiedName(location, "binaryContent"));
                // Plain Base64 avoids MIME line breaks inside FOXML text content.
                binary.setTextContent(encode(entry.source()));
                version.replaceChild(binary, location);
            }
            // Transformer output is UTF-8 without a BOM; Akubra parses the XML encoding
            // declaration.
            transform(document, target);
        }
    }

    private static String encode(Path source) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (InputStream input = Files.newInputStream(source);
                OutputStream output = Base64.getEncoder().wrap(encoded)) {
            input.transferTo(output);
        }
        return encoded.toString(StandardCharsets.US_ASCII);
    }

    private static Element findVersion(Document document, String dsid, String versionId) {
        NodeList streams = document.getElementsByTagNameNS(FOXML_NAMESPACE, "datastream");
        for (int index = 0; index < streams.getLength(); index++) {
            Element stream = (Element) streams.item(index);
            if (!stream.getAttribute("ID").equals(dsid)) {
                continue;
            }
            NodeList versions = stream.getElementsByTagNameNS(FOXML_NAMESPACE, "datastreamVersion");
            for (int versionIndex = 0; versionIndex < versions.getLength(); versionIndex++) {
                Element version = (Element) versions.item(versionIndex);
                if (version.getAttribute("ID").equals(versionId)) {
                    return version;
                }
            }
        }
        throw new IllegalStateException(
                "Planned datastream disappeared: " + dsid + "/" + versionId);
    }

    private static Element firstChild(Element parent, String localName) {
        NodeList children = parent.getElementsByTagNameNS(FOXML_NAMESPACE, localName);
        return children.getLength() == 0 ? null : (Element) children.item(0);
    }

    private static String qualifiedName(Element source, String localName) {
        String prefix = source.getPrefix();
        return prefix == null ? localName : prefix + ":" + localName;
    }

    private static Document parse(Path file) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(file.toFile());
        } catch (ParserConfigurationException | SAXException exception) {
            throw new IOException("Could not parse FOXML " + file, exception);
        }
    }

    private static void transform(Document document, Path target) throws IOException {
        try (OutputStream output = Files.newOutputStream(target)) {
            var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            transformer.transform(new DOMSource(document), new StreamResult(output));
        } catch (TransformerException exception) {
            throw new IOException("Could not write rewritten FOXML " + target, exception);
        }
    }

    private record PlannedReferences(
            List<InlineEntry> entries, List<String> warnings, List<Path> missing) {}

    public record InlineEntry(
            Path object,
            String pid,
            String fileName,
            String dsid,
            String versionId,
            String mimeType,
            Path source) {}

    public record InlinePlan(List<Path> objects, List<InlineEntry> entries, List<String> warnings) {
        public InlinePlan {
            objects = List.copyOf(objects);
            entries = List.copyOf(entries);
            warnings = List.copyOf(warnings);
        }
    }
}

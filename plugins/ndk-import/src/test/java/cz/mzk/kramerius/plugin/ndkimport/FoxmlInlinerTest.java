// Exercises FOXML preflight and rewriting from README.md → ndk-import → How a run works steps 3–4.
package cz.mzk.kramerius.plugin.ndkimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class FoxmlInlinerTest {
    private static final String NAMESPACE = "info:fedora/fedora-system:def/foxml#";
    private static final Path FIXTURE =
            Path.of("src/test/resources/fixtures/ndk-batch/215af974-9656-11f1-8afd-bed0c7abae6b");
    @TempDir Path temporaryDirectory;

    @Test
    void plansTheTwoPageDatastreams() throws Exception {
        Path batch = copyFixture();
        FoxmlInliner.InlinePlan plan = new FoxmlInliner().plan(batch, configuration(batch));
        assertEquals(
                List.of("ALTO", "TEXT_OCR"),
                plan.entries().stream().map(FoxmlInliner.InlineEntry::dsid).sorted().toList());
        assertEquals(
                List.of("ALTO.0", "TEXT_OCR.0"),
                plan.entries().stream().map(FoxmlInliner.InlineEntry::versionId).sorted().toList());
        assertTrue(
                plan.entries().stream().allMatch(entry -> entry.mimeType().equals("text/plain")));
        assertTrue(plan.warnings().isEmpty());
    }

    @Test
    void reportsEveryMissingFile() throws Exception {
        Path batch = copyFixture();
        Files.delete(batch.resolve("alto_215af974-9656-11f1-8afd-bed0c7abae6b_0001.xml.alto"));
        IOException error =
                assertThrows(
                        IOException.class,
                        () -> new FoxmlInliner().plan(batch, configuration(batch)));
        assertTrue(error.getMessage().contains("alto_215af974"));

        Path second = copyFixture();
        Files.delete(second.resolve("alto_215af974-9656-11f1-8afd-bed0c7abae6b_0001.xml.alto"));
        Files.delete(second.resolve("txt_215af974-9656-11f1-8afd-bed0c7abae6b_0001.txt"));
        IOException both =
                assertThrows(
                        IOException.class,
                        () -> new FoxmlInliner().plan(second, configuration(second)));
        assertTrue(both.getMessage().contains("alto_215af974"));
        assertTrue(both.getMessage().contains("txt_215af974"));
    }

    @Test
    void rewritesInlineFilesAndCopiesOtherObjectsUnchanged() throws Exception {
        Path batch = copyFixture();
        FoxmlInliner inliner = new FoxmlInliner();
        FoxmlInliner.InlinePlan plan = inliner.plan(batch, configuration(batch));
        Path target = temporaryDirectory.resolve("rewritten");
        inliner.write(plan, target);
        Path output = target.resolve("2de03d33-90a5-11f1-8749-1261b7a65813.xml");
        Document document = parse(output);
        assertEquals(
                0,
                byId(document, "datastream", "ALTO")
                        .getElementsByTagNameNS(NAMESPACE, "contentLocation")
                        .getLength());
        assertEquals(
                0,
                byId(document, "datastream", "TEXT_OCR")
                        .getElementsByTagNameNS(NAMESPACE, "contentLocation")
                        .getLength());
        assertFalse(
                Files.exists(
                        target.resolve("alto_215af974-9656-11f1-8afd-bed0c7abae6b_0001.xml.alto")));
        assertFalse(
                Files.exists(target.resolve("txt_215af974-9656-11f1-8afd-bed0c7abae6b_0001.txt")));
        assertTrue(
                Files.mismatch(
                                batch.resolve("215af974-9656-11f1-8afd-bed0c7abae6b.xml"),
                                target.resolve("215af974-9656-11f1-8afd-bed0c7abae6b.xml"))
                        == -1);
        assertTrue(document.getElementsByTagNameNS(NAMESPACE, "binaryContent").getLength() == 2);
        assertDecodedEquals(
                batch, document, "ALTO", "alto_215af974-9656-11f1-8afd-bed0c7abae6b_0001.xml.alto");
        assertDecodedEquals(
                batch, document, "TEXT_OCR", "txt_215af974-9656-11f1-8afd-bed0c7abae6b_0001.txt");
        Element img = byId(document, "datastream", "IMG_FULL");
        assertEquals("E", img.getAttribute("CONTROL_GROUP"));
        assertTrue(img.getElementsByTagNameNS(NAMESPACE, "contentLocation").getLength() == 1);
        assertEquals(
                "https://imageserver.mzk.cz/NDK/2026/08/215af974-9656-11f1-8afd-bed0c7abae6b/uc_215af974-9656-11f1-8afd-bed0c7abae6b_0001/big.jpg",
                img.getElementsByTagNameNS(NAMESPACE, "contentLocation")
                        .item(0)
                        .getAttributes()
                        .getNamedItem("REF")
                        .getNodeValue());
        assertEquals(0, img.getElementsByTagNameNS(NAMESPACE, "binaryContent").getLength());
        assertEquals(
                "http://local.fedora.server/fedora/get/policy:private/POLICYDEF",
                byId(document, "datastream", "POLICY")
                        .getElementsByTagNameNS(NAMESPACE, "contentLocation")
                        .item(0)
                        .getAttributes()
                        .getNamedItem("REF")
                        .getNodeValue());
        assertMetadataPreserved(document, "ALTO", "ALTO.0", "2026-08-13T01:36:37.212Z");
        assertMetadataPreserved(document, "TEXT_OCR", "TEXT_OCR.0", "2026-08-13T01:36:37.211Z");
        assertTrue(Files.size(output) > 0);
    }

    @Test
    void preservesEveryOtherDatastreamSerialization() throws Exception {
        Path batch = copyFixture();
        FoxmlInliner inliner = new FoxmlInliner();
        inliner.write(
                inliner.plan(batch, configuration(batch)), temporaryDirectory.resolve("preserved"));
        Document input = parse(batch.resolve("2de03d33-90a5-11f1-8749-1261b7a65813.xml"));
        Document output =
                parse(
                        temporaryDirectory.resolve(
                                "preserved/2de03d33-90a5-11f1-8749-1261b7a65813.xml"));
        NodeList before = input.getElementsByTagNameNS(NAMESPACE, "datastream");
        NodeList after = output.getElementsByTagNameNS(NAMESPACE, "datastream");
        for (int index = 0; index < before.getLength(); index++) {
            Element original = (Element) before.item(index);
            String id = original.getAttribute("ID");
            if (id.equals("ALTO") || id.equals("TEXT_OCR")) continue;
            assertEquals(serialize(original), serialize(byId(output, "datastream", id)), id);
        }
    }

    @Test
    void rejectsSourceOutsideConfiguredRoots() throws Exception {
        Path batch = copyFixture();
        Path external = temporaryDirectory.resolve("outside.txt");
        Files.writeString(external, "outside");
        replaceTextReference(
                batch,
                "file:/kramerius_ndk/import/215af974-9656-11f1-8afd-bed0c7abae6b/txt_215af974-9656-11f1-8afd-bed0c7abae6b_0001.txt",
                external.toUri().toString());
        NdkImportConfiguration config =
                new NdkImportConfiguration(
                        temporaryDirectory.resolve("root"),
                        List.of(),
                        List.of("ALTO", "TEXT_OCR"),
                        temporaryDirectory.resolve("work"),
                        true,
                        java.time.Duration.ofHours(24),
                        batch);
        IOException error =
                assertThrows(IOException.class, () -> new FoxmlInliner().plan(batch, config));
        assertTrue(error.getMessage().contains("uuid:2de03d33-90a5-11f1-8749-1261b7a65813"));
        assertTrue(error.getMessage().contains("TEXT_OCR"));
        assertTrue(error.getMessage().contains(external.toUri().toString()));
    }

    @Test
    void resolvesPlainRelativeReferenceUnderBatchDirectory() throws Exception {
        Path batch = copyFixture();
        String filename = "txt_215af974-9656-11f1-8afd-bed0c7abae6b_0001.txt";
        replaceTextReference(
                batch,
                "file:/kramerius_ndk/import/215af974-9656-11f1-8afd-bed0c7abae6b/" + filename,
                filename);
        FoxmlInliner.InlinePlan plan = new FoxmlInliner().plan(batch, configuration(batch));
        assertTrue(
                plan.entries().stream()
                        .anyMatch(
                                entry ->
                                        entry.dsid().equals("TEXT_OCR")
                                                && entry.source().equals(batch.resolve(filename))));
    }

    @Test
    void reportsMalformedFileReferenceWithObjectContext() throws Exception {
        Path batch = copyFixture();
        replaceTextReference(
                batch,
                "file:/kramerius_ndk/import/215af974-9656-11f1-8afd-bed0c7abae6b/txt_215af974-9656-11f1-8afd-bed0c7abae6b_0001.txt",
                "file:%ZZ");
        IOException error =
                assertThrows(
                        IOException.class,
                        () -> new FoxmlInliner().plan(batch, configuration(batch)));
        assertTrue(error.getMessage().contains("uuid:2de03d33-90a5-11f1-8749-1261b7a65813"));
        assertTrue(error.getMessage().contains("TEXT_OCR"));
        assertTrue(error.getMessage().contains("file:%ZZ"));
    }

    @Test
    void missingSourcesUseSpecificExceptionWithAllPaths() throws Exception {
        Path batch = copyFixture();
        Files.delete(batch.resolve("alto_215af974-9656-11f1-8afd-bed0c7abae6b_0001.xml.alto"));
        Files.delete(batch.resolve("txt_215af974-9656-11f1-8afd-bed0c7abae6b_0001.txt"));
        MissingSourceFilesException error =
                assertThrows(
                        MissingSourceFilesException.class,
                        () -> new FoxmlInliner().plan(batch, configuration(batch)));
        assertEquals(2, error.missingFiles().size());
    }

    @Test
    void warnsAboutNonInternalizedFileReferenceOutsideRoots() throws Exception {
        Path batch = copyFixture();
        Path page = batch.resolve("2de03d33-90a5-11f1-8749-1261b7a65813.xml");
        String xml = Files.readString(page, java.nio.charset.StandardCharsets.UTF_8);
        xml =
                xml.replace(
                        "https://imageserver.mzk.cz/NDK/2026/08/215af974-9656-11f1-8afd-bed0c7abae6b/uc_215af974-9656-11f1-8afd-bed0c7abae6b_0001/big.jpg",
                        "file:/somewhere/else/big.jpg");
        Files.writeString(page, xml, java.nio.charset.StandardCharsets.UTF_8);

        FoxmlInliner.InlinePlan plan = new FoxmlInliner().plan(batch, configuration(batch));

        assertEquals(1, plan.warnings().size()); // left alone, so its location is never checked
    }

    @Test
    void warnsAndLeavesNonInternalizedFileReferenceUntouched() throws Exception {
        Path batch = copyFixture();
        Path page = batch.resolve("2de03d33-90a5-11f1-8749-1261b7a65813.xml");
        String xml = Files.readString(page, java.nio.charset.StandardCharsets.UTF_8);
        xml =
                xml.replace(
                        "https://imageserver.mzk.cz/NDK/2026/08/215af974-9656-11f1-8afd-bed0c7abae6b/uc_215af974-9656-11f1-8afd-bed0c7abae6b_0001/big.jpg",
                        "file:/kramerius_ndk/import/215af974-9656-11f1-8afd-bed0c7abae6b/alto_215af974-9656-11f1-8afd-bed0c7abae6b_0001.xml.alto");
        Files.writeString(page, xml, java.nio.charset.StandardCharsets.UTF_8);
        FoxmlInliner inliner = new FoxmlInliner();
        FoxmlInliner.InlinePlan plan = inliner.plan(batch, configuration(batch));
        assertEquals(1, plan.warnings().size());
        Path target = temporaryDirectory.resolve("warned");
        inliner.write(plan, target);
        assertEquals(
                "file:/kramerius_ndk/import/215af974-9656-11f1-8afd-bed0c7abae6b/alto_215af974-9656-11f1-8afd-bed0c7abae6b_0001.xml.alto",
                byId(parse(target.resolve(page.getFileName())), "datastream", "IMG_FULL")
                        .getElementsByTagNameNS(NAMESPACE, "contentLocation")
                        .item(0)
                        .getAttributes()
                        .getNamedItem("REF")
                        .getNodeValue());
    }

    private Path copyFixture() throws IOException {
        Path target =
                temporaryDirectory
                        .resolve("fixture-" + System.nanoTime())
                        .resolve(FIXTURE.getFileName());
        Files.createDirectories(target);
        try (var files = Files.list(FIXTURE)) {
            for (Path file : files.toList()) {
                Files.copy(file, target.resolve(file.getFileName()));
            }
        }
        return target;
    }

    private NdkImportConfiguration configuration(Path batch) {
        return new NdkImportConfiguration(
                temporaryDirectory,
                List.of(
                        new NdkImportConfiguration.PathMapping(
                                Path.of("/kramerius_ndk/import/"), batch.getParent())),
                List.of("ALTO", "TEXT_OCR"),
                temporaryDirectory.resolve("work"),
                true,
                java.time.Duration.ofHours(24),
                batch);
    }

    private static Document parse(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(file.toFile());
    }

    private static String serialize(org.w3c.dom.Node node) throws Exception {
        var transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
        var output = new java.io.StringWriter();
        transformer.transform(
                new javax.xml.transform.dom.DOMSource(node),
                new javax.xml.transform.stream.StreamResult(output));
        return output.toString();
    }

    private static void replaceTextReference(Path batch, String before, String after)
            throws IOException {
        Path page = batch.resolve("2de03d33-90a5-11f1-8749-1261b7a65813.xml");
        String xml =
                Files.readString(page, java.nio.charset.StandardCharsets.UTF_8)
                        .replace(before, after);
        Files.writeString(page, xml, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Element byId(Document document, String name, String id) {
        NodeList elements = document.getElementsByTagNameNS(NAMESPACE, name);
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (element.getAttribute("ID").equals(id)) return element;
        }
        throw new AssertionError("Missing " + id);
    }

    private static void assertDecodedEquals(
            Path batch, Document document, String dsid, String filename) throws Exception {
        Element stream = byId(document, "datastream", dsid);
        String encoded =
                stream.getElementsByTagNameNS(NAMESPACE, "binaryContent").item(0).getTextContent();
        assertTrue(
                java.util.Arrays.equals(
                        Files.readAllBytes(batch.resolve(filename)),
                        Base64.getDecoder().decode(encoded)));
    }

    private static void assertMetadataPreserved(
            Document document, String dsid, String versionId, String created) {
        Element version = versionById(byId(document, "datastream", dsid), versionId);
        assertEquals(versionId, version.getAttribute("ID"));
        assertEquals(created, version.getAttribute("CREATED"));
        assertEquals("text/plain", version.getAttribute("MIMETYPE"));
    }

    private static Element versionById(Element datastream, String versionId) {
        NodeList versions = datastream.getElementsByTagNameNS(NAMESPACE, "datastreamVersion");
        for (int index = 0; index < versions.getLength(); index++) {
            Element version = (Element) versions.item(index);
            if (version.getAttribute("ID").equals(versionId)) return version;
        }
        throw new AssertionError("Missing version " + versionId);
    }
}

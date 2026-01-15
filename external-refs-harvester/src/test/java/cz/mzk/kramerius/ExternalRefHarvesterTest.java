package cz.mzk.kramerius;

import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ExternalRefsHarvesterTest {

    private ExternalRefsHarvester harvester;
    private SimpleSolrBatchUpdater mockUpdater;
    private Method processFoxmlMethod;

    @BeforeEach
    void setup() throws Exception {
        mockUpdater = mock(SimpleSolrBatchUpdater.class);

        harvester = new ExternalRefsHarvester();

        Field batchUpdaterField = ExternalRefsHarvester.class.getDeclaredField("batchUpdater");
        batchUpdaterField.setAccessible(true);
        batchUpdaterField.set(harvester, mockUpdater); // inject mock

        Field ignoreDsSetField = ExternalRefsHarvester.class.getDeclaredField("ignoreDsSet");
        ignoreDsSetField.setAccessible(true);
        ignoreDsSetField.set(harvester, java.util.Set.of("POLICY"));

        processFoxmlMethod = ExternalRefsHarvester.class.getDeclaredMethod("processFoxml", String.class, File.class);
        processFoxmlMethod.setAccessible(true);
    }

    @Test
    void testProcessFoxml() throws Exception {
        String pid = "uuid:12345";

        // Small Foxml snippet as test input
        String foxmlContent = """
                <digitalObject xmlns="info:fedora/fedora-system:def/foxml#" VERSION="1.1" PID="uuid:12345">
                  <datastream ID="ALTO" CONTROL_GROUP="M" STATE="A" VERSIONABLE="false">
                    <datastreamVersion ID="ALTO.0" CREATED="2026-01-09T14:06:50.805Z" MIMETYPE="application/xml">
                      <content/>
                    </datastreamVersion>
                  </datastream>
                  <datastream ID="IMG_FULL" CONTROL_GROUP="E" STATE="A" VERSIONABLE="false">
                    <datastreamVersion ID="IMG_FULL.0" CREATED="2026-01-09T14:06:50.805Z" MIMETYPE="image/jpeg">
                      <contentLocation TYPE="URL" REF="http://is.example.org/12345.jpeg"/>
                    </datastreamVersion>
                  </datastream>
                  <datastream ID="POLICY" CONTROL_GROUP="E" STATE="A" VERSIONABLE="false">
                      <datastreamVersion ID="POLICY.0" LABEL="" CREATED="2024-01-29T16:35:16.985Z" MIMETYPE="application/rdf+xml">
                          <contentLocation TYPE="URL" REF="http://local.fedora.server/fedora/get/policy:private/POLICYDEF"/>
                      </datastreamVersion>
                  </datastream>
                  </digitalObject>
                                """;

        // Write FoxML to a temporary file
        Path tempFile = Files.createTempFile("test-foxml", ".xml");
        Files.writeString(tempFile, foxmlContent);
        File foxmlFile = tempFile.toFile();

        // Call method
        processFoxmlMethod.invoke(harvester, pid, foxmlFile);

        // Capture SolrInputDocuments added to batchUpdater
        ArgumentCaptor<SolrInputDocument> captor = ArgumentCaptor.forClass(SolrInputDocument.class);
        verify(mockUpdater, times(1)).add(captor.capture());

        SolrInputDocument doc = captor.getValue();
        assertEquals(pid + "!IMG_FULL", doc.getFieldValue("id"));
        assertEquals(pid, doc.getFieldValue("pid"));
        assertEquals("IMG_FULL", doc.getFieldValue("ds"));
        assertEquals("http://is.example.org/12345.jpeg", doc.getFieldValue("uri"));

        // Cleanup temp file
        foxmlFile.delete();
    }

    @Test
    void testProcessNsPrefixedFoxml() throws Exception {
        String pid = "uuid:12345";

        // Small Foxml snippet as test input
        String foxmlContent = """
                <foxml:digitalObject xmlns="info:fedora/fedora-system:def/foxml#" VERSION="1.1" PID="uuid:12345">
                  <foxml:datastream ID="IMG_FULL" CONTROL_GROUP="E" STATE="A" VERSIONABLE="false">
                    <foxml:datastreamVersion ID="IMG_FULL.0" CREATED="2026-01-09T14:06:50.805Z" MIMETYPE="image/jpeg">
                      <foxml:contentLocation TYPE="URL" REF="http://is.example.org/12345-old.jpeg"/>
                    </foxml:datastreamVersion>
                    <foxml:datastreamVersion ID="IMG_FULL.1" CREATED="2026-01-09T14:06:50.805Z" MIMETYPE="image/jpeg">
                      <foxml:contentLocation TYPE="URL" REF="http://is.example.org/12345.jpeg"/>
                    </foxml:datastreamVersion>
                  </foxml:datastream>
                  </foxml:digitalObject>
                               """;

        // Write FoxML to a temporary file
        Path tempFile = Files.createTempFile("test-foxml", ".xml");
        Files.writeString(tempFile, foxmlContent);
        File foxmlFile = tempFile.toFile();

        // Call method
        processFoxmlMethod.invoke(harvester, pid, foxmlFile);

        // Capture SolrInputDocuments added to batchUpdater
        ArgumentCaptor<SolrInputDocument> captor = ArgumentCaptor.forClass(SolrInputDocument.class);
        verify(mockUpdater, times(1)).add(captor.capture());

        SolrInputDocument doc = captor.getValue();
        assertEquals(pid + "!IMG_FULL", doc.getFieldValue("id"));
        assertEquals(pid, doc.getFieldValue("pid"));
        assertEquals("IMG_FULL", doc.getFieldValue("ds"));
        assertEquals("http://is.example.org/12345.jpeg", doc.getFieldValue("uri"));

        // Cleanup temp file
        foxmlFile.delete();
    }
}

package cz.mzk.kramerius.plugin;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ExternalRefsHarvesterTest {

    private static final String FOXML = """
            <digitalObject xmlns="info:fedora/fedora-system:def/foxml#" VERSION="1.1" PID="uuid:12345">
              <datastream ID="ALTO" CONTROL_GROUP="M" STATE="A" VERSIONABLE="false">
                <datastreamVersion ID="ALTO.0" MIMETYPE="application/xml"><content/></datastreamVersion>
              </datastream>
              <datastream ID="IMG_FULL" CONTROL_GROUP="E" STATE="A" VERSIONABLE="false">
                <datastreamVersion ID="IMG_FULL.0" MIMETYPE="image/jpeg">
                  <contentLocation TYPE="URL" REF="http://is.example.org/12345-old.jpeg"/>
                </datastreamVersion>
                <datastreamVersion ID="IMG_FULL.1" MIMETYPE="image/jpeg">
                  <contentLocation TYPE="URL" REF="http://is.example.org/12345.jpeg"/>
                </datastreamVersion>
              </datastream>
              <datastream ID="POLICY" CONTROL_GROUP="E" STATE="A" VERSIONABLE="false">
                <datastreamVersion ID="POLICY.0" MIMETYPE="application/rdf+xml">
                  <contentLocation TYPE="URL" REF="http://local.fedora.server/fedora/get/policy:private/POLICYDEF"/>
                </datastreamVersion>
              </datastream>
            </digitalObject>
            """;

    private static final String PREFIXED_FOXML = """
            <foxml:digitalObject xmlns:foxml="info:fedora/fedora-system:def/foxml#" VERSION="1.1" PID="uuid:12345">
              <foxml:datastream ID="IMG_FULL" CONTROL_GROUP="E" STATE="A" VERSIONABLE="false">
                <foxml:datastreamVersion ID="IMG_FULL.0" MIMETYPE="image/jpeg">
                  <foxml:contentLocation TYPE="URL" REF="http://is.example.org/12345.jpeg"/>
                </foxml:datastreamVersion>
              </foxml:datastream>
            </foxml:digitalObject>
            """;

    @TempDir
    Path store;

    private final FakeSolrClient solr = new FakeSolrClient();

    private ExternalRefsHarvester harvester() {
        return new ExternalRefsHarvester(solr, "external-refs", Set.of("POLICY"), List.of(Duration.ZERO, Duration.ZERO));
    }

    private Path object(String uuid, String content) throws IOException {
        Path dir = Files.createDirectories(store.resolve("00/11"));
        return Files.writeString(dir.resolve("info%3Afedora%2Fuuid%3A" + uuid), content);
    }

    @Test
    void extractsLatestVersionOfExternalDatastreams() throws Exception {
        List<SolrInputDocument> docs = harvester().extractRefs("uuid:12345", FOXML.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, docs.size());
        assertEquals("uuid:12345!IMG_FULL", docs.get(0).getFieldValue("id"));
        assertEquals("uuid:12345", docs.get(0).getFieldValue("pid"));
        assertEquals("IMG_FULL", docs.get(0).getFieldValue("ds"));
        assertEquals("http://is.example.org/12345.jpeg", docs.get(0).getFieldValue("uri"));
    }

    @Test
    void extractsFromPrefixedFoxml() throws Exception {
        List<SolrInputDocument> docs = harvester().extractRefs("uuid:12345", PREFIXED_FOXML.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, docs.size());
        assertEquals("http://is.example.org/12345.jpeg", docs.get(0).getFieldValue("uri"));
    }

    @Test
    void recognizesOnlyUuidObjectFiles() {
        assertEquals("uuid:1", ExternalRefsHarvester.pidFromFileName("info%3Afedora%2Fuuid%3A1"));
        assertNull(ExternalRefsHarvester.pidFromFileName("info%3Afedora%2Fmodel%3Apage"));
        assertNull(ExternalRefsHarvester.pidFromFileName("something.txt"));
    }

    @Test
    void successfulRunIndexesEveryRefAndRecordsState() throws Exception {
        // More than one batch, so that both the full batches and the flush are covered
        for (int i = 0; i < 1201; i++) {
            object("a" + i, FOXML);
        }

        harvester().harvest(store, 4, false);

        assertEquals(1202, solr.documents.size());
        assertEquals(1201, solr.documents.stream().filter(d -> d.getFieldValue("pid") != null).count());
        assertNotNull(solr.state());
        assertTrue(solr.committed);
    }

    @Test
    void secondRunSkipsFilesOlderThanLastSuccessfulRun() throws Exception {
        Path old = object("old", FOXML);
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        object("recent", FOXML);
        solr.existingState = Date.from(Instant.now().minus(Duration.ofDays(1)));

        harvester().harvest(store, 2, false);

        assertEquals(List.of("uuid:recent"), solr.pids());
    }

    @Test
    void fullRunIgnoresLastSuccessfulRun() throws Exception {
        Path old = object("old", FOXML);
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        solr.existingState = Date.from(Instant.now());

        harvester().harvest(store, 2, true);

        assertEquals(List.of("uuid:old"), solr.pids());
    }

    @Test
    void corruptedObjectsAreSkippedAndRunCompletes() throws Exception {
        object("good", FOXML);
        object("malformed", "<not-xml");
        object("empty", "");
        // Invalid UTF-8, which the parser reports as an IOException
        Files.write(store.resolve("00/11/info%3Afedora%2Fuuid%3Abinary"), new byte[]{'<', 'a', '>', (byte) 0xC3, (byte) 0x28, '<', '/', 'a', '>'});
        // Well-formed, but a datastream without an ID
        object("noid", "<digitalObject><datastream CONTROL_GROUP=\"E\"/></digitalObject>");

        long corrupted = harvester().harvest(store, 2, false);

        assertEquals(4, corrupted);
        assertEquals(List.of("uuid:good"), solr.pids());
        assertNotNull(solr.state());
        assertTrue(solr.committed);
    }

    @Test
    void storageReadFailureFailsTheRunAndKeepsState() throws Exception {
        object("good", FOXML);
        Files.createSymbolicLink(store.resolve("00/11/info%3Afedora%2Fuuid%3Agone"), store.resolve("missing"));

        assertThrows(IOException.class, () -> harvester().harvest(store, 2, false));

        assertNull(solr.state());
    }

    @Test
    void fullScanFlagsUnseenRefsAsOrphaned() throws Exception {
        object("live", FOXML);
        solr.staleRefs.add(staleRef("uuid:gone", "IMG_FULL"));

        harvester().harvest(store, 2, false);

        assertEquals(List.of("uuid:gone!IMG_FULL"), solr.orphaned());
    }

    @Test
    void forcedFullScanFlagsUnseenRefsAsOrphaned() throws Exception {
        solr.existingState = Date.from(Instant.now());
        solr.staleRefs.add(staleRef("uuid:gone", "IMG_FULL"));

        harvester().harvest(store, 2, true);

        assertEquals(List.of("uuid:gone!IMG_FULL"), solr.orphaned());
    }

    @Test
    void incrementalRunFlagsNothing() throws Exception {
        solr.existingState = Date.from(Instant.now().minus(Duration.ofDays(1)));
        solr.staleRefs.add(staleRef("uuid:gone", "IMG_FULL"));

        harvester().harvest(store, 2, false);

        assertEquals(List.of(), solr.orphaned());
    }

    @Test
    void refsThatWereNotCheckedAreNotFlagged() throws Exception {
        object("broken", "<not-xml");
        solr.staleRefs.add(staleRef("uuid:broken", "IMG_FULL"));
        solr.staleRefs.add(staleRef("uuid:live", "POLICY"));

        harvester().harvest(store, 2, false);

        assertEquals(List.of(), solr.orphaned());
    }

    @Test
    void failedScanFlagsNothing() throws Exception {
        object("a", FOXML);
        Files.createSymbolicLink(store.resolve("00/11/info%3Afedora%2Fuuid%3Agone"), store.resolve("missing"));
        solr.staleRefs.add(staleRef("uuid:gone", "IMG_FULL"));

        assertThrows(IOException.class, () -> harvester().harvest(store, 2, false));

        assertEquals(List.of(), solr.orphaned());
    }

    private static SolrDocument staleRef(String pid, String ds) {
        SolrDocument doc = new SolrDocument();
        doc.setField("id", pid + "!" + ds);
        doc.setField("pid", pid);
        doc.setField("ds", ds);
        return doc;
    }

    @Test
    void solrUpdateFailureFailsTheRunAndKeepsState() throws Exception {
        object("a", FOXML);
        solr.failUpdates = true;

        assertThrows(IOException.class, () -> harvester().harvest(store, 2, false));

        assertNull(solr.state());
    }

    @Test
    void shortSolrOutageIsRetriedAndRunCompletes() throws Exception {
        object("a", FOXML);
        solr.updateFailuresLeft.set(2);

        harvester().harvest(store, 2, false);

        assertEquals(List.of("uuid:a"), solr.pids());
        assertNotNull(solr.state());
    }

    @Test
    void serverErrorIsRetried() throws Exception {
        object("a", FOXML);
        solr.updateFailuresLeft.set(1);
        solr.updateFailure = new SolrException(SolrException.ErrorCode.SERVICE_UNAVAILABLE, "no leader");

        harvester().harvest(store, 2, false);

        assertNotNull(solr.state());
    }

    @Test
    void rejectedDocumentsAreNotRetried() throws Exception {
        object("a", FOXML);
        solr.updateFailuresLeft.set(1);
        solr.updateFailure = new SolrException(SolrException.ErrorCode.BAD_REQUEST, "invalid document");

        assertThrows(IOException.class, () -> harvester().harvest(store, 2, false));

        assertEquals(1, solr.updateAttempts.get());
        assertNull(solr.state());
    }

    @Test
    void flaggingIsRetried() throws Exception {
        object("a", FOXML);
        solr.staleRefs.add(staleRef("uuid:gone", "IMG_FULL"));
        solr.queryFailuresAfterFirst.set(1);

        harvester().harvest(store, 2, false);

        assertEquals(List.of("uuid:gone!IMG_FULL"), solr.orphaned());
    }

    @Test
    void unreachableSolrFailsBeforeTheWalk() throws Exception {
        object("a", FOXML);
        solr.failQueries = true;

        assertThrows(SolrServerException.class, () -> harvester().harvest(store, 2, false));

        assertTrue(solr.documents.isEmpty());
    }

    private static class FakeSolrClient extends SolrClient {
        final List<SolrInputDocument> documents = Collections.synchronizedList(new ArrayList<>());
        final List<SolrDocument> staleRefs = new ArrayList<>();
        Date existingState;
        boolean failUpdates;
        boolean failQueries;
        boolean committed;
        final AtomicInteger updateAttempts = new AtomicInteger();
        final AtomicInteger updateFailuresLeft = new AtomicInteger();
        final AtomicInteger queryFailuresAfterFirst = new AtomicInteger();
        final AtomicInteger queries = new AtomicInteger();
        RuntimeException updateFailure;

        @Override
        public NamedList<Object> request(SolrRequest request, String collection) throws SolrServerException {
            NamedList<Object> response = new NamedList<>();
            if (request instanceof UpdateRequest) {
                updateAttempts.incrementAndGet();
                if (failUpdates) {
                    throw new SolrServerException("update failed");
                }
                if (updateFailuresLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                    if (updateFailure != null) {
                        throw updateFailure;
                    }
                    throw new SolrServerException("Timeout occurred while waiting response from server");
                }
                UpdateRequest update = (UpdateRequest) request;
                if (update.getDocuments() != null) {
                    documents.addAll(update.getDocuments());
                }
                if (update.getAction() != null) {
                    committed = true;
                }
                return response;
            }
            if (failQueries) {
                throw new SolrServerException("query failed");
            }
            if (queries.getAndIncrement() > 0 && queryFailuresAfterFirst.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                throw new SolrServerException("query timed out");
            }
            SolrDocumentList results = new SolrDocumentList();
            if ("/select".equals(((QueryRequest) request).getPath()) || request.getPath() == null) {
                String cursor = request.getParams().get("cursorMark");
                if ("*".equals(cursor)) {
                    results.addAll(staleRefs);
                }
                response.add("response", results);
                response.add("nextCursorMark", "done");
                return response;
            }
            if (existingState != null) {
                SolrDocument state = new SolrDocument();
                state.setField("id", ExternalRefsHarvester.STATE_DOC_ID);
                state.setField("timestamp", existingState);
                results.add(state);
            }
            response.add("response", results);
            return response;
        }

        SolrInputDocument state() {
            return documents.stream()
                    .filter(d -> ExternalRefsHarvester.STATE_DOC_ID.equals(d.getFieldValue("id")))
                    .findFirst().orElse(null);
        }

        List<Object> orphaned() {
            return documents.stream()
                    .filter(d -> Map.of("set", true).equals(d.getFieldValue("orphaned")))
                    .map(d -> d.getFieldValue("id"))
                    .toList();
        }

        List<Object> pids() {
            return documents.stream()
                    .map(d -> d.getFieldValue("pid"))
                    .filter(pid -> pid != null)
                    .toList();
        }

        @Override
        public void close() {
        }
    }
}

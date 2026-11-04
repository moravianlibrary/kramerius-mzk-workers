package cz.mzk.kramerius.plugin;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CursorMarkParams;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Harvests the latest contentLocation of externally managed datastreams from FOXML files into Solr.
 *
 * <p>The timestamp of the last fully successful run is kept in the {@link #STATE_DOC_ID} document.
 * Only a run without any failure moves it forward, so a failed run is repeated in full by the next one.</p>
 *
 * <p>Corrupted objects are not failures: they are logged and skipped, and read again once their file changes.
 * Failing on them would keep a repository with corrupted objects from ever finishing a run.</p>
 *
 * <p>A successful scan of the whole store rewrites every ref it finds, so a ref older than the scan no longer
 * exists in the store and is flagged as orphaned. A ref that comes back is rewritten, which resets the flag
 * to its schema default. Incremental runs cannot tell and do not flag anything.</p>
 *
 * <p>Solr calls made during a run are retried, because one timeout must not cost a scan that takes days.
 * Every one of them is idempotent: adds overwrite the same ids, and flagging sets the same value.</p>
 */
public class ExternalRefsHarvester {

    public static final Logger LOGGER = Logger.getLogger(ExternalRefsHarvester.class.getName());

    static final String STATE_DOC_ID = "__harvest_state__";

    private static final int BATCH_SIZE = 500;
    private static final int MAX_QUEUED_TASKS = 10000;
    // File mtimes come from the storage clock, doc timestamps from the Solr clock, the run start from the worker clock
    private static final Duration SINCE_OVERLAP = Duration.ofHours(1);
    // About 30 minutes in total, to outlast a Solr restart or a leader election
    private static final List<Duration> RETRY_DELAYS = List.of(
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2),
            Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofMinutes(5));

    private final SolrClient solrClient;
    private final String collection;
    private final Set<String> ignoredDatastreams;
    private final List<Duration> retryDelays;

    private final List<SolrInputDocument> batch = new ArrayList<>(BATCH_SIZE);
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final Set<String> corruptedPids = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean solrFailed = new AtomicBoolean();

    public ExternalRefsHarvester(SolrClient solrClient, String collection, Set<String> ignoredDatastreams) {
        this(solrClient, collection, ignoredDatastreams, RETRY_DELAYS);
    }

    ExternalRefsHarvester(SolrClient solrClient, String collection, Set<String> ignoredDatastreams, List<Duration> retryDelays) {
        this.solrClient = solrClient;
        this.collection = collection;
        this.ignoredDatastreams = ignoredDatastreams;
        this.retryDelays = retryDelays;
    }

    /**
     * @return the number of skipped corrupted objects
     */
    public long harvest(Path storeRoot, int threads, boolean full) throws IOException, SolrServerException, InterruptedException {
        Instant runStart = Instant.now();
        Instant since = full ? Instant.EPOCH : lastSuccessfulRun();
        LOGGER.info("Harvesting " + storeRoot + " modified since " + since + " with " + threads + " threads");

        // When the queue is full, the walking thread reads the file itself, which keeps memory usage bounded
        ThreadPoolExecutor readers = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_TASKS), new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            walk(storeRoot, since, readers);
        } finally {
            readers.shutdown();
            readers.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        }
        flush();

        if (failed.get() > 0) {
            throw new IOException("Harvest failed: " + failed.get() + " failures after " + processed.get() + " processed objects");
        }

        SolrInputDocument state = new SolrInputDocument();
        state.addField("id", STATE_DOC_ID);
        state.addField("timestamp", Date.from(runStart));
        withRetries("save the harvest state", () -> solrClient.add(collection, state));
        withRetries("commit", () -> solrClient.commit(collection));
        LOGGER.info("Harvest finished, processed objects: " + processed.get() + ", skipped corrupted objects: " + corruptedPids.size());

        // After saving the state: failing here must not cost another scan of the whole store
        if (since.equals(Instant.EPOCH)) {
            markOrphaned(runStart.minus(SINCE_OVERLAP));
        }
        return corruptedPids.size();
    }

    /**
     * Refs of corrupted objects and ignored datastreams were not checked by the scan, so they stay unflagged.
     * Uses atomic updates, so the other fields stay as they are.
     */
    private void markOrphaned(Instant cutoff) throws IOException, SolrServerException {
        SolrQuery query = new SolrQuery("timestamp:[* TO " + cutoff.truncatedTo(ChronoUnit.SECONDS) + "}");
        query.addFilterQuery("-id:" + STATE_DOC_ID, "-orphaned:true");
        query.setFields("id", "pid", "ds");
        query.setRows(BATCH_SIZE);
        query.setSort("id", SolrQuery.ORDER.asc);

        long marked = 0;
        String cursor = CursorMarkParams.CURSOR_MARK_START;
        while (true) {
            query.set(CursorMarkParams.CURSOR_MARK_PARAM, cursor);
            QueryResponse response = withRetries("query refs to flag", () -> solrClient.query(collection, query));

            List<SolrInputDocument> updates = new ArrayList<>();
            for (SolrDocument doc : response.getResults()) {
                if (corruptedPids.contains(doc.getFieldValue("pid")) || ignoredDatastreams.contains(doc.getFieldValue("ds"))) {
                    continue;
                }
                SolrInputDocument update = new SolrInputDocument();
                update.addField("id", doc.getFieldValue("id"));
                update.addField("orphaned", Map.of("set", true));
                updates.add(update);
            }
            if (!updates.isEmpty()) {
                withRetries("flag orphaned refs", () -> solrClient.add(collection, updates));
                marked += updates.size();
            }

            String next = response.getNextCursorMark();
            if (next.equals(cursor)) {
                break;
            }
            cursor = next;
        }
        withRetries("commit", () -> solrClient.commit(collection));
        LOGGER.info("Orphaned refs flagged: " + marked);
    }

    private Instant lastSuccessfulRun() throws IOException, SolrServerException {
        SolrDocument state = solrClient.getById(collection, STATE_DOC_ID);
        if (state == null) {
            return Instant.EPOCH;
        }
        return ((Date) state.getFieldValue("timestamp")).toInstant().minus(SINCE_OVERLAP);
    }

    private void walk(Path storeRoot, Instant since, ThreadPoolExecutor readers) throws IOException {
        Files.walkFileTree(storeRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                return Files.isHidden(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (solrFailed.get()) {
                    LOGGER.severe("Stopping the walk after a Solr failure");
                    return FileVisitResult.TERMINATE;
                }
                String pid = pidFromFileName(file.getFileName().toString());
                if (pid != null && !attrs.lastModifiedTime().toInstant().isBefore(since)) {
                    readers.execute(() -> processObject(pid, file));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                failed.incrementAndGet();
                LOGGER.log(Level.SEVERE, "Failed to visit file: " + file, e);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * @return the PID of an object file named like info%3Afedora%2Fuuid%3A..., null for any other file
     */
    static String pidFromFileName(String fileName) {
        String[] parts = URLDecoder.decode(fileName, StandardCharsets.UTF_8).split("/");
        if (parts.length != 2 || !parts[0].equals("info:fedora") || !parts[1].startsWith("uuid:")) {
            return null;
        }
        return parts[1];
    }

    private void processObject(String pid, Path file) {
        byte[] foxml;
        try {
            foxml = Files.readAllBytes(file);
        } catch (IOException e) {
            failed.incrementAndGet();
            LOGGER.log(Level.SEVERE, "Failed to read object: " + pid, e);
            return;
        }

        List<SolrInputDocument> docs;
        try {
            docs = extractRefs(pid, foxml);
        } catch (Exception e) {
            corruptedPids.add(pid);
            LOGGER.warning("Skipping corrupted object " + pid + " (" + file + "): " + e);
            return;
        }

        for (SolrInputDocument doc : docs) {
            add(doc);
        }
        long count = processed.incrementAndGet();
        if (count % 10000 == 0) {
            LOGGER.info("Processed objects: " + count);
        }
    }

    List<SolrInputDocument> extractRefs(String pid, byte[] foxml) throws Exception {
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(foxml));

        XPath xpath = XPathFactory.newInstance().newXPath();
        XPathExpression dsExpr = xpath.compile("//*[local-name()='datastream' and @CONTROL_GROUP='E']");
        XPathExpression refExpr = xpath.compile(
                ".//*[local-name()='datastreamVersion'][last()]/*[local-name()='contentLocation']/@REF");

        List<SolrInputDocument> docs = new ArrayList<>();
        NodeList dsNodes = (NodeList) dsExpr.evaluate(document, XPathConstants.NODESET);
        for (int i = 0; i < dsNodes.getLength(); i++) {
            Node dsNode = dsNodes.item(i);
            String dsId = dsNode.getAttributes().getNamedItem("ID").getNodeValue();
            if (ignoredDatastreams.contains(dsId)) {
                continue;
            }

            NodeList refNodes = (NodeList) refExpr.evaluate(dsNode, XPathConstants.NODESET);
            for (int j = 0; j < refNodes.getLength(); j++) {
                SolrInputDocument doc = new SolrInputDocument();
                doc.addField("id", pid + "!" + dsId);
                doc.addField("pid", pid);
                doc.addField("ds", dsId);
                doc.addField("uri", refNodes.item(j).getNodeValue());
                docs.add(doc);
            }
        }
        return docs;
    }

    private void add(SolrInputDocument doc) {
        List<SolrInputDocument> full = null;
        synchronized (batch) {
            batch.add(doc);
            if (batch.size() >= BATCH_SIZE) {
                full = new ArrayList<>(batch);
                batch.clear();
            }
        }
        if (full != null) {
            send(full);
        }
    }

    private void flush() {
        List<SolrInputDocument> rest;
        synchronized (batch) {
            rest = new ArrayList<>(batch);
            batch.clear();
        }
        if (!rest.isEmpty()) {
            send(rest);
        }
    }

    private void send(List<SolrInputDocument> docs) {
        try {
            withRetries("send a batch of " + docs.size() + " documents", () -> solrClient.add(collection, docs));
        } catch (Exception e) {
            failed.addAndGet(docs.size());
            solrFailed.set(true);
            LOGGER.log(Level.SEVERE, "Failed to send a batch of " + docs.size() + " documents", e);
        }
    }

    /**
     * A waiting thread holds up its reader, which slows the walk down instead of piling up work while Solr is away.
     */
    private <T> T withRetries(String action, SolrCall<T> call) throws IOException, SolrServerException {
        for (int attempt = 0; ; attempt++) {
            try {
                return call.run();
            } catch (IOException | SolrServerException | SolrException e) {
                if (attempt >= retryDelays.size() || solrFailed.get() || !isTransient(e)) {
                    throw e;
                }
                Duration delay = retryDelays.get(attempt);
                LOGGER.warning("Failed to " + action + ", retry " + (attempt + 1) + "/" + retryDelays.size() + " in " + delay + ": " + e);
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting to retry", interrupted);
                }
            }
        }
    }

    /**
     * A request Solr rejects, such as an invalid document, fails the same way every time.
     */
    private static boolean isTransient(Exception e) {
        return !(e instanceof SolrException) || ((SolrException) e).code() >= 500;
    }

    @FunctionalInterface
    private interface SolrCall<T> {
        T run() throws IOException, SolrServerException;
    }
}

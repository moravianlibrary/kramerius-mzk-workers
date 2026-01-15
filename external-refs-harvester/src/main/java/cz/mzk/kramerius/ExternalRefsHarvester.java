package cz.mzk.kramerius;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.*;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Command(name = "external-refs-harvester", mixinStandardHelpOptions = true, version = "1.0.0", description = "Harvest external datastream refs from FoxML objects into Solr")
public class ExternalRefsHarvester implements Runnable {

    private static final String COLLECTION = "external-refs";

    private static final Logger logger = LoggerFactory.getLogger(ExternalRefsHarvester.class);

    @Parameters(index = "0", description = "Path to object store")
    private Path storePath;

    @Parameters(index = "1", description = "Solr base URL")
    private String solrUrl;

    @Option(names = { "-t", "--threads" }, description = "Number of object readers threads")
    private int threads = Runtime.getRuntime().availableProcessors() - 2;

    @Option(names = { "-b", "--batch" }, description = "Solr index batch size")
    private int batchSize = 500;

    @Option(names = { "-i", "--ignore-ds" }, description = "Datastream IDs to ignore")
    private String[] ignoreDss;

    private SolrClient solrClient;
    private SimpleSolrBatchUpdater batchUpdater;
    private ExecutorService objectReaders;
    private AtomicInteger processedCount = new AtomicInteger(0);
    private Set<String> ignoreDsSet = Set.of();

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ExternalRefsHarvester()).execute(args);
        System.exit(exitCode);
    }

    public ExternalRefsHarvester() {
    }

    @Override
    public void run() {
        try {
            init();

            if (ignoreDss != null && ignoreDss.length > 0) {
                ignoreDsSet = Set.of(ignoreDss);
            }

            Instant since = getLastIndexedTimestamp();

            processStore(storePath, since);

            objectReaders.shutdown();
            // wait for tasks to finish
            while (!objectReaders.isTerminated()) {
                Thread.sleep(500);
            }

            batchUpdater.shutdown();
            solrClient.close();
            logger.info("Harvesting completed successfully.");
        } catch (Exception e) {
            logger.error("Error during harvesting", e);
        }
    }

    public void init() {
        this.solrClient = new Http2SolrClient.Builder(
                solrUrl.endsWith(COLLECTION) ? solrUrl.substring(0, solrUrl.length() - COLLECTION.length() - 1)
                        : solrUrl)
                .build();
        this.batchUpdater = new SimpleSolrBatchUpdater(batchSize, 1, this.solrClient, COLLECTION);
        this.objectReaders = Executors.newFixedThreadPool(threads);
        logger.info("Initialized ExternalRefsHarvester with {} threads and batch size {}", threads, batchSize);

    }

    private Instant getLastIndexedTimestamp() {
        try {
            SolrQuery query = new SolrQuery("*:*");
            query.setRows(1);
            query.setSort("timestamp", SolrQuery.ORDER.desc);

            QueryResponse response = solrClient.query(COLLECTION, query);
            SolrDocumentList docs = response.getResults();

            if (!docs.isEmpty()) {
                SolrDocument doc = docs.get(0);
                Object ts = doc.getFieldValue("timestamp");
                if (ts != null) {
                    // Solr usually returns ISO 8601 string
                    return ((Date) ts).toInstant();
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to query last indexed timestamp, defaulting to epoch", e);
        }
        return Instant.EPOCH;
    }

    public void processStore(Path storePath, Instant since) throws Exception {
        logger.info("Starting traversal of store: {}", storePath);

        Files.walkFileTree(storePath, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                try {
                    if (Files.isHidden(dir)) {
                        logger.debug("Skipping hidden directory: {}", dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                } catch (IOException e) {
                    logger.warn("Failed to check if directory is hidden: {}", dir, e);
                    return FileVisitResult.SKIP_SUBTREE;
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    if (attrs.lastModifiedTime().toInstant().isBefore(since)) {
                        return FileVisitResult.CONTINUE;
                    }

                    String decoded = URLDecoder.decode(
                            file.getFileName().toString(),
                            StandardCharsets.UTF_8);

                    // info:fedora/uuid:{uuid}
                    if (!decoded.startsWith("info:fedora/")) {
                        logger.debug("Skipping non-fedora object file: {}", file);
                        return FileVisitResult.CONTINUE;
                    }

                    String[] parts = decoded.split("/");
                    if (parts.length < 2 || !parts[1].startsWith("uuid:")) {
                        logger.warn("Invalid datastream filename structure: {}", file);
                        return FileVisitResult.CONTINUE;
                    }

                    String pid = parts[1];

                    objectReaders.submit(() -> {
                        try {
                            processFoxml(pid, file.toFile());
                            processedCount.incrementAndGet();

                            if (processedCount.get() % 1000 == 0) {
                                logger.info("Processed {} objects so far...", processedCount.get());
                            }
                        } catch (Exception e) {
                            logger.error("Failed to process Foxml for object: {}", pid, e);
                        }
                    });
                } catch (Exception e) {
                    logger.error("Failed to parse datastream file: {}", file, e);
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                logger.error("Failed to visit file: {}", file, exc);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void processFoxml(String pid, File foxml) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(foxml);

        XPathFactory xPathFactory = XPathFactory.newInstance();
        XPath xpath = xPathFactory.newXPath();
        xpath.setNamespaceContext(new FoxmlNamespaceContext());

        // select all datastream elements
        XPathExpression dsExpr = xpath.compile(
                "//*[local-name()='datastream' and @CONTROL_GROUP='E']");
        XPathExpression refExpr = xpath.compile(
                ".//*[local-name()='datastreamVersion'][last()]"
                        + "/*[local-name()='contentLocation']/@REF");

        NodeList dsNodes = (NodeList) dsExpr.evaluate(doc, XPathConstants.NODESET);

        for (int i = 0; i < dsNodes.getLength(); i++) {
            Node dsNode = dsNodes.item(i);
            String dsId = dsNode.getAttributes().getNamedItem("ID").getNodeValue();

            if (ignoreDsSet.contains(dsId)) {
                continue;
            }

            // select all contentLocation/@REF inside this datastream
            NodeList refNodes = (NodeList) refExpr.evaluate(dsNode, XPathConstants.NODESET);

            for (int j = 0; j < refNodes.getLength(); j++) {
                String ref = refNodes.item(j).getNodeValue();

                SolrInputDocument solrDoc = new SolrInputDocument();
                solrDoc.addField("id", pid + "!" + dsId); // unique key
                solrDoc.addField("pid", pid);
                solrDoc.addField("ds", dsId);
                solrDoc.addField("uri", ref);

                batchUpdater.add(solrDoc);
            }
        }
    }

    public void close() throws Exception {
        if (objectReaders != null) {
            objectReaders.shutdown();
        }
        if (batchUpdater != null) {
            batchUpdater.shutdown();
        }
        if (solrClient != null) {
            solrClient.close();
        }
        logger.info("ExternalRefsHarvester closed.");
    }
}

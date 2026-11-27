package com.logstream.ingestion;

import com.logstream.alert.AlertDispatcher;
import com.logstream.alert.AlertEvaluator;
import com.logstream.alert.AlertHistoryRepository;
import com.logstream.alert.AlertManagementServiceImpl;
import com.logstream.alert.AlertRuleRepository;
import com.logstream.alert.AlertScheduler;
import com.logstream.alert.v1.AlertManagementServiceGrpc;
import com.logstream.gateway.RestGateway;
import com.logstream.index.IndexManager;
import com.logstream.index.LogSearchService;
import com.logstream.index.LogSearchServiceImpl;
import com.logstream.index.LuceneLogSink;
import com.logstream.index.QueryParserUtil;
import com.logstream.search.v1.LogSearchServiceGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

public final class LogIngestionServer {
    private static final Logger logger = LoggerFactory.getLogger(LogIngestionServer.class);
    private static final int DEFAULT_PORT = 50051;

    // Lucene configuration
    private static final String LUCENE_INDEX_PATH = System.getenv().getOrDefault("LOGSTREAM_LUCENE_PATH", "data/lucene-index");
    private static final int LUCENE_RAM_BUFFER_MB = Integer.parseInt(System.getenv().getOrDefault("LOGSTREAM_LUCENE_RAM_BUFFER_MB", "256"));
    private static final int LUCENE_MAX_BUFFERED_DOCS = Integer.parseInt(System.getenv().getOrDefault("LOGSTREAM_LUCENE_MAX_BUFFERED_DOCS", "1000"));
    private static final long LUCENE_FLUSH_INTERVAL_DOCS = Long.parseLong(System.getenv().getOrDefault("LOGSTREAM_LUCENE_FLUSH_INTERVAL_DOCS", "1000"));

    // Search service configuration
    private static final int SEARCH_DEFAULT_PAGE_SIZE = Integer.parseInt(System.getenv().getOrDefault("LOGSTREAM_SEARCH_DEFAULT_PAGE_SIZE", "50"));
    private static final int SEARCH_MAX_PAGE_SIZE = Integer.parseInt(System.getenv().getOrDefault("LOGSTREAM_SEARCH_MAX_PAGE_SIZE", "500"));

    // Alerting configuration
    private static final int ALERT_SCHEDULER_POOL_SIZE = Integer.parseInt(System.getenv().getOrDefault("LOGSTREAM_ALERT_SCHEDULER_POOL_SIZE", "4"));
    private static final int ALERT_WEBHOOK_TIMEOUT_SECONDS = Integer.parseInt(System.getenv().getOrDefault("LOGSTREAM_ALERT_WEBHOOK_TIMEOUT_SECONDS", "10"));
    private static final String SMTP_HOST = System.getenv().getOrDefault("LOGSTREAM_SMTP_HOST", "");
    private static final int SMTP_PORT = Integer.parseInt(System.getenv().getOrDefault("LOGSTREAM_SMTP_PORT", "587"));
    private static final String SMTP_USERNAME = System.getenv().getOrDefault("LOGSTREAM_SMTP_USERNAME", "");
    private static final String SMTP_PASSWORD = System.getenv().getOrDefault("LOGSTREAM_SMTP_PASSWORD", "");
    private static final String EMAIL_FROM = System.getenv().getOrDefault("LOGSTREAM_EMAIL_FROM", "alerts@logstream.local");

    // REST Gateway configuration
    private static final int REST_GATEWAY_PORT = Integer.parseInt(System.getenv().getOrDefault("LOGSTREAM_REST_PORT", "8080"));

    private LogIngestionServer() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        String databasePath = System.getenv().getOrDefault("LOGSTREAM_DB_PATH", "data/logstream.db");

        SqliteLogSink sqliteSink;
        try {
            sqliteSink = new SqliteLogSink(Path.of(databasePath));
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to initialize SQLite log database", exception);
        }

        // Initialize Lucene index manager
        IndexManager indexManager;
        try {
            indexManager = IndexManager.create(Path.of(LUCENE_INDEX_PATH), LUCENE_RAM_BUFFER_MB, LUCENE_MAX_BUFFERED_DOCS);
        } catch (IOException exception) {
            sqliteSink.close();
            throw new IllegalStateException("Failed to initialize Lucene index", exception);
        }

        // Create Lucene log sink (dual-writes to SQLite + Lucene)
        LuceneLogSink luceneSink = new LuceneLogSink(sqliteSink, indexManager, LUCENE_FLUSH_INTERVAL_DOCS);

        // Create search service
        QueryParserUtil queryParser = new QueryParserUtil(indexManager.getAnalyzer());
        LogSearchService searchService = new LogSearchService(indexManager, queryParser, SEARCH_DEFAULT_PAGE_SIZE, SEARCH_MAX_PAGE_SIZE);
        LogSearchServiceImpl searchServiceImpl = new LogSearchServiceImpl(searchService, SEARCH_DEFAULT_PAGE_SIZE, SEARCH_MAX_PAGE_SIZE);

        // Create alerting components
        AlertRuleRepository ruleRepository = new AlertRuleRepository(databasePath);
        AlertHistoryRepository historyRepository = new AlertHistoryRepository(databasePath);
        AlertEvaluator evaluator = new AlertEvaluator(searchService, queryParser);
        AlertDispatcher dispatcher = new AlertDispatcher(SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD, EMAIL_FROM, ALERT_WEBHOOK_TIMEOUT_SECONDS);
        AlertScheduler alertScheduler = new AlertScheduler(ruleRepository, historyRepository, evaluator, dispatcher, searchService, ALERT_SCHEDULER_POOL_SIZE);
        AlertManagementServiceImpl alertManagementService = new AlertManagementServiceImpl(alertScheduler);

        // Start alert scheduler
        alertScheduler.start();

        // Start REST gateway
        RestGateway restGateway = new RestGateway(REST_GATEWAY_PORT, "localhost", port,
                ruleRepository, historyRepository, alertScheduler);
        restGateway.start();

        Server server = ServerBuilder.forPort(port)
                .addService(new LogIngestionServiceImpl(luceneSink))
                .addService(searchServiceImpl)
                .addService(alertManagementService)
                .build()
                .start();

        logger.info("Log ingestion gRPC server listening on port {}", server.getPort());
        logger.info("  - SQLite database: {}", databasePath);
        logger.info("  - Lucene index: {} (ramBufferMB={}, maxBufferedDocs={})", LUCENE_INDEX_PATH, LUCENE_RAM_BUFFER_MB, LUCENE_MAX_BUFFERED_DOCS);
        logger.info("  - Search service: defaultPageSize={}, maxPageSize={}", SEARCH_DEFAULT_PAGE_SIZE, SEARCH_MAX_PAGE_SIZE);
        logger.info("  - Alert scheduler: poolSize={}, rules={}", ALERT_SCHEDULER_POOL_SIZE, alertScheduler.getScheduledRuleCount());
        logger.info("  - REST gateway: port={}", REST_GATEWAY_PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down log ingestion server");
            server.shutdown();
            restGateway.stop();
            try {
                if (!server.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
                alertScheduler.close();
                dispatcher.close();
                historyRepository.close();
                ruleRepository.close();
                luceneSink.close(); // This closes both Lucene and SQLite
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                logger.error("Interrupted while shutting down log ingestion server", exception);
            } catch (RuntimeException exception) {
                logger.error("Failed to close log sinks", exception);
            }
        }));
        server.awaitTermination();
    }
}

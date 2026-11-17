package com.griddb.solar.service;

import com.griddb.solar.model.SolarRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Service for all GridDB Web API interactions.
 * Handles container creation, data ingestion (batched PUT), and SQL queries.
 */
@Service
public class GridDbService {

    private static final Logger log = LoggerFactory.getLogger(GridDbService.class);

    @Value("${griddb.container-name:solar_radiation_records}")
    private String containerName;

    private static final int BATCH_SIZE = 2000;
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${griddb.base-url}")
    private String baseUrl;

    @Value("${griddb.username}")
    private String username;

    @Value("${griddb.password}")
    private String password;

    @Value("${griddb.api-key:}")
    private String apiKey;

    // ─── Container Management ───────────────────────────────────────────────

    /**
     * Creates the solar radiation TIME_SERIES container.
     * Safe to call multiple times – 409 (already exists) is silently ignored.
     */
    public void createContainer() throws Exception {
        String body = """
                {
                  "container_name": "%s",
                  "container_type": "TIME_SERIES",
                  "rowkey": true,
                  "columns": [
                    {"name": "timestamp",            "type": "TIMESTAMP"},
                    {"name": "regionId",             "type": "STRING"},
                    {"name": "regionName",           "type": "STRING"},
                    {"name": "latitude",             "type": "DOUBLE"},
                    {"name": "longitude",            "type": "DOUBLE"},
                    {"name": "ghiKwhM2Day",          "type": "DOUBLE"},
                    {"name": "dniKwhM2Day",          "type": "DOUBLE"},
                    {"name": "temperatureCelsius",   "type": "DOUBLE"},
                    {"name": "cloudCoverFraction",   "type": "DOUBLE"},
                    {"name": "month",                "type": "INTEGER"},
                    {"name": "year",                 "type": "INTEGER"},
                    {"name": "season",               "type": "STRING"}
                  ]
                }
                """.formatted(containerName);

        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/containers"))
                .header("Authorization", authHeader())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        if (code == 201 || code == 200) {
            log.info("Container '{}' created.", containerName);
        } else if (code == 409) {
            log.info("Container '{}' already exists – skipping creation.", containerName);
        } else if (code == 403) {
            log.warn("HTTP 403 Forbidden from GridDB (IP not allowlisted?). " +
                    "Add your public IP to the GridDB Cloud portal → Connection → IP Allowlist.");
        } else {
            log.warn("Create container returned HTTP {}: {}", code, resp.body());
        }
    }

    // ─── Data Ingestion ─────────────────────────────────────────────────────

    /**
     * Persists solar records to GridDB in batches of {@value BATCH_SIZE}.
     */
    public void persist(List<SolarRecord> records) throws Exception {
        if (records.isEmpty()) return;

        for (int i = 0; i < records.size(); i += BATCH_SIZE) {
            List<SolarRecord> batch = records.subList(i, Math.min(i + BATCH_SIZE, records.size()));
            List<List<Object>> rows = new ArrayList<>();

            for (SolarRecord r : batch) {
                rows.add(List.of(
                        TS_FMT.format(r.timestamp()),
                        r.regionId(),
                        r.regionName(),
                        r.latitude(),
                        r.longitude(),
                        r.ghiKwhM2Day(),
                        r.dniKwhM2Day(),
                        r.temperatureCelsius(),
                        r.cloudCoverFraction(),
                        r.month(),
                        r.year(),
                        r.season()
                ));
            }

            String payload = mapper.writeValueAsString(rows);
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/containers/" + containerName + "/rows"))
                    .header("Authorization", authHeader())
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException(
                        "Batch PUT failed: HTTP " + resp.statusCode() + " → " + resp.body());
            }

            int end = Math.min(i + BATCH_SIZE, records.size());
            log.info("Persisted rows {}/{}", end, records.size());
        }
    }

    // ─── SQL Queries ─────────────────────────────────────────────────────────

    /**
     * Executes a SQL SELECT via the GridDB /sql endpoint.
     */
    public JsonNode query(String sql) throws Exception {
        String body = mapper.writeValueAsString(
                List.of(Map.of("type", "sql-select", "stmt", sql)));

        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/sql"))
                .header("Authorization", authHeader())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            if (resp.statusCode() == 403) {
                log.error("GridDB returned 403 Forbidden. Your IP is likely not in the allowlist.");
                throw new IllegalStateException(
                        "GridDB access denied (403). Add your public IP to the GridDB Cloud portal allowlist.");
            }
            log.error("SQL query failed HTTP {}: {}", resp.statusCode(), resp.body());
            throw new IllegalStateException("SQL query failed: " + resp.statusCode());
        }

        JsonNode root = mapper.readTree(resp.body());
        JsonNode results = root.get(0).get("results");
        return results != null ? results : mapper.createArrayNode();
    }

    /**
     * Returns the total row count in the container.
     */
    public long countRows() {
        try {
            JsonNode results = query("SELECT COUNT(*) FROM " + containerName);
            if (results.isArray() && results.size() > 0) {
                JsonNode firstRow = results.get(0);
                if (firstRow.isArray() && firstRow.size() > 0) {
                    return firstRow.get(0).asLong(0);
                }
            }
        } catch (Exception e) {
            log.warn("Could not count rows (container may not exist yet): {}", e.getMessage());
        }
        return 0;
    }

    // ─── Internal helpers ────────────────────────────────────────────────────

    private String authHeader() {
        if (apiKey != null && !apiKey.isBlank()) {
            return "Basic " + apiKey;
        }
        return "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}

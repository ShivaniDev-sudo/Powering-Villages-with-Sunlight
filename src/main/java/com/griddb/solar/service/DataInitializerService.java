package com.griddb.solar.service;

import com.griddb.solar.model.SolarRecord;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * On application startup:
 * <ol>
 *   <li>Creates the GridDB container (idempotent).</li>
 *   <li>If the container is empty, checks for real solar CSV data (NASA POWER).</li>
 *   <li>Falls back to realistic synthetic data if no CSV found.</li>
 * </ol>
 */
@Service
public class DataInitializerService {

    private static final Logger log = LoggerFactory.getLogger(DataInitializerService.class);

    private final GridDbService gridDb;
    private final SolarDataIngestionService csvIngestion;
    private final NasaPowerClientService nasaClient;
    private final SyntheticSolarDataService syntheticData;

    @Value("${solar.data.dir:src/main/resources/data}")
    private String dataDir;

    @Value("${app.seed-data.enabled:true}")
    private boolean seedEnabled;

    @Value("${nasa.api.enabled:true}")
    private boolean nasaApiEnabled;

    public DataInitializerService(GridDbService gridDb,
                                  SolarDataIngestionService csvIngestion,
                                  NasaPowerClientService nasaClient,
                                  SyntheticSolarDataService syntheticData) {
        this.gridDb = gridDb;
        this.csvIngestion = csvIngestion;
        this.nasaClient = nasaClient;
        this.syntheticData = syntheticData;
    }

    @PostConstruct
    public void initialize() {
        log.info("=== Solar Potential Analytics – startup initialization ===");

        // 1. Ensure container exists
        try {
            gridDb.createContainer();
        } catch (Exception e) {
            log.error("Failed to create GridDB container: {}", e.getMessage(), e);
            return;
        }

        // 2. Check if data already present
        long existingRows = gridDb.countRows();
        if (existingRows > 0) {
            log.info("Container already has {} rows – skipping data load.", existingRows);
            return;
        }

        // 3. Try loading from staged local CSV dataset first
        List<SolarRecord> records = tryLoadLocalCsv();

        // 4. If no local CSV, fetch live from NASA POWER API via pure Java HTTP client
        if (records.isEmpty() && nasaApiEnabled) {
            try {
                log.info("No local CSV found – querying NASA POWER API directly in Java...");
                records = nasaClient.fetchAllRegions(2019, 2023);
            } catch (Exception e) {
                log.warn("NASA POWER API query failed ({}) – falling back to synthetic generator", e.getMessage());
            }
        }

        // 5. Fall back to synthetic data if offline or API unavailable
        if (records.isEmpty() && seedEnabled) {
            log.info("Generating synthetic solar radiation dataset as fallback.");
            records = syntheticData.generate();
        }

        if (records.isEmpty()) {
            log.warn("No data generated or found – dashboard will show empty charts.");
            return;
        }

        try {
            log.info("Ingesting {} solar records into GridDB ...", records.size());
            gridDb.persist(records);
            log.info("Ingestion complete.");
        } catch (Exception e) {
            log.error("Ingestion failed: {}", e.getMessage(), e);
        }
    }

    private List<SolarRecord> tryLoadLocalCsv() {
        Path dir = Path.of(dataDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }

        List<String> candidateNames = List.of("solar_radiation_nasa.csv", "solar.csv", "nasa_power_solar_data.csv");
        for (String name : candidateNames) {
            Path file = dir.resolve(name);
            if (Files.exists(file)) {
                try {
                    log.info("Found staged solar dataset: {}", file);
                    return csvIngestion.load(file);
                } catch (Exception e) {
                    log.error("Failed to load {}: {}", file, e.getMessage(), e);
                }
            }
        }
        return List.of();
    }
}

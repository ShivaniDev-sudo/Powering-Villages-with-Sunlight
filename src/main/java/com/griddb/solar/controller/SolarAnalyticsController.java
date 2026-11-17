package com.griddb.solar.controller;

import com.griddb.solar.service.GridDbService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller exposing solar analytics endpoints consumed by the dashboard.
 *
 * <p>All queries run as SQL inside GridDB – the application receives compact result sets.
 */
@RestController
@RequestMapping("/api")
public class SolarAnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(SolarAnalyticsController.class);

    @org.springframework.beans.factory.annotation.Value("${griddb.container-name:solar_radiation_records}")
    private String containerName;

    private final GridDbService gridDb;

    public SolarAnalyticsController(GridDbService gridDb) {
        this.gridDb = gridDb;
    }

    // ─── Monthly average GHI ─────────────────────────────────────────────────

    /**
     * Average daily GHI by month across all regions and years.
     * Response: [[month, avgGhi, avgDni], ...]
     */
    @GetMapping("/monthly-avg")
    public ResponseEntity<String> monthlyAvg() {
        return runQuery("""
                SELECT month, AVG(ghiKwhM2Day) AS avgGhi, AVG(dniKwhM2Day) AS avgDni
                FROM %s
                GROUP BY month
                ORDER BY month
                """.formatted(containerName));
    }

    // ─── Regional comparison ─────────────────────────────────────────────────

    /**
     * Average annual GHI and DNI by region, sorted by GHI descending.
     * Response: [[regionName, avgGhi, avgDni], ...]
     */
    @GetMapping("/regional")
    public ResponseEntity<String> regional() {
        return runQuery("""
                SELECT regionName, AVG(ghiKwhM2Day) AS avgGhi, AVG(dniKwhM2Day) AS avgDni
                FROM %s
                GROUP BY regionName
                ORDER BY avgGhi DESC
                """.formatted(containerName));
    }

    // ─── Seasonal variation ──────────────────────────────────────────────────

    /**
     * Average GHI by season and region for seasonal heatmap analysis.
     * Response: [[season, regionName, avgGhi], ...]
     */
    @GetMapping("/seasonal")
    public ResponseEntity<String> seasonal() {
        return runQuery("""
                SELECT season, regionName, AVG(ghiKwhM2Day) AS avgGhi
                FROM %s
                GROUP BY season, regionName
                ORDER BY season, avgGhi DESC
                """.formatted(containerName));
    }

    // ─── Year-over-year trend ────────────────────────────────────────────────

    /**
     * Average GHI by year for trend analysis.
     * Response: [[year, avgGhi, avgCloud], ...]
     */
    @GetMapping("/trend")
    public ResponseEntity<String> trend() {
        return runQuery("""
                SELECT year, AVG(ghiKwhM2Day) AS avgGhi, AVG(cloudCoverFraction) AS avgCloud
                FROM %s
                GROUP BY year
                ORDER BY year
                """.formatted(containerName));
    }

    // ─── Best regions ────────────────────────────────────────────────────────

    /**
     * Top regions by annual GHI potential (annualized kWh/m²).
     * Response: [[regionName, avgGhi, totalDays, annualKwh], ...]
     */
    @GetMapping("/best-regions")
    public ResponseEntity<String> bestRegions() {
        return runQuery("""
                SELECT regionName, AVG(ghiKwhM2Day) AS avgGhi, COUNT(*) AS totalDays
                FROM %s
                GROUP BY regionName
                ORDER BY avgGhi DESC
                """.formatted(containerName));
    }

    // ─── Cloud cover impact ──────────────────────────────────────────────────

    /**
     * Monthly cloud cover vs GHI correlation.
     * Response: [[month, avgCloud, avgGhi], ...]
     */
    @GetMapping("/cloud-impact")
    public ResponseEntity<String> cloudImpact() {
        return runQuery("""
                SELECT month, AVG(cloudCoverFraction) AS avgCloud, AVG(ghiKwhM2Day) AS avgGhi
                FROM %s
                GROUP BY month
                ORDER BY month
                """.formatted(containerName));
    }

    // ─── KPI summary ─────────────────────────────────────────────────────────

    /**
     * Aggregate KPI numbers: record count, average GHI, max GHI, year range.
     * Response: [[totalRecords, avgGhi, maxGhi, minYear, maxYear], ...]
     */
    @GetMapping("/summary")
    public ResponseEntity<String> summary() {
        return runQuery("""
                SELECT COUNT(*) AS totalRecords,
                       AVG(ghiKwhM2Day) AS avgGhi,
                       MAX(ghiKwhM2Day) AS maxGhi,
                       MIN(year) AS minYear,
                       MAX(year) AS maxYear
                FROM %s
                """.formatted(containerName));
    }

    // ─── Temperature vs GHI ──────────────────────────────────────────────────

    /**
     * Monthly temperature vs GHI for correlation analysis.
     * Response: [[month, avgTemp, avgGhi], ...]
     */
    @GetMapping("/temp-correlation")
    public ResponseEntity<String> tempCorrelation() {
        return runQuery("""
                SELECT month, AVG(temperatureCelsius) AS avgTemp, AVG(ghiKwhM2Day) AS avgGhi
                FROM %s
                GROUP BY month
                ORDER BY month
                """.formatted(containerName));
    }

    // ─── Internal helper ─────────────────────────────────────────────────────

    private ResponseEntity<String> runQuery(String sql) {
        try {
            JsonNode results = gridDb.query(sql.trim());
            return ResponseEntity.ok(results.toString());
        } catch (Exception e) {
            log.error("Query failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body("{\"error\": \"" + e.getMessage() + "\"}");
        }
    }
}

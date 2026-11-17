package com.griddb.solar.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.griddb.solar.model.SolarRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Pure Java HTTP client that fetches real satellite solar radiation observations
 * directly from the NASA POWER (Prediction Of Worldwide Energy Resources) REST API.
 */
@Service
public class NasaPowerClientService {

    private static final Logger log = LoggerFactory.getLogger(NasaPowerClientService.class);

    private static final Object[][] REGIONS = {
            {"RAJ", "Rajasthan (Jodhpur)",     26.29, 73.02},
            {"GUJ", "Gujarat (Kutch)",         23.73, 69.86},
            {"LAD", "Ladakh (Leh)",            34.15, 77.58},
            {"TN",  "Tamil Nadu (Ramanathapuram)", 9.37, 78.83},
            {"AP",  "Andhra Pradesh (Anantapur)", 14.68, 77.60},
            {"MP",  "Madhya Pradesh (Bhopal)", 23.26, 77.41},
            {"KAR", "Karnataka (Bellary)",     15.14, 76.92},
            {"MAH", "Maharashtra (Nashik)",    20.00, 73.78},
            {"UP",  "Uttar Pradesh (Jhansi)",  25.45, 78.57},
            {"ASM", "Assam (Guwahati)",        26.14, 91.74},
            {"WB",  "West Bengal (Purulia)",   23.33, 86.37},
            {"JK",  "Jammu & Kashmir (Jammu)", 32.73, 74.87},
    };

    private static final Map<Integer, String> MONTH_SEASONS = Map.ofEntries(
            Map.entry(1, "Winter"), Map.entry(2, "Winter"), Map.entry(3, "Summer"),
            Map.entry(4, "Summer"), Map.entry(5, "Summer"), Map.entry(6, "Monsoon"),
            Map.entry(7, "Monsoon"), Map.entry(8, "Monsoon"), Map.entry(9, "Post-Monsoon"),
            Map.entry(10, "Post-Monsoon"), Map.entry(11, "Winter"), Map.entry(12, "Winter")
    );

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Fetches daily solar records for all configured regions concurrently.
     *
     * @param startYear e.g. 2019
     * @param endYear   e.g. 2023
     * @return List of parsed SolarRecord objects
     */
    public List<SolarRecord> fetchAllRegions(int startYear, int endYear) {
        log.info("Fetching real satellite observations from NASA POWER API for {} regions ({} - {})...",
                REGIONS.length, startYear, endYear);

        List<CompletableFuture<List<SolarRecord>>> futures = new ArrayList<>();

        for (int i = 0; i < REGIONS.length; i++) {
            final int regionIndex = i;
            final Object[] reg = REGIONS[i];
            futures.add(CompletableFuture.supplyAsync(() -> fetchRegion(regionIndex, reg, startYear, endYear)));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<SolarRecord> allRecords = new ArrayList<>();
        for (CompletableFuture<List<SolarRecord>> future : futures) {
            try {
                allRecords.addAll(future.join());
            } catch (Exception e) {
                log.error("Failed to collect NASA POWER data: {}", e.getMessage(), e);
            }
        }

        log.info("Successfully fetched {} solar records from NASA POWER API in pure Java.", allRecords.size());
        return allRecords;
    }

    private List<SolarRecord> fetchRegion(int regionIndex, Object[] reg, int startYear, int endYear) {
        String regionId = (String) reg[0];
        String regionName = (String) reg[1];
        double lat = (double) reg[2];
        double lon = (double) reg[3];

        String url = String.format(
                "https://power.larc.nasa.gov/api/temporal/daily/point?" +
                "parameters=ALLSKY_SFC_SW_DWN,ALLSKY_SFC_SW_DNI,T2M,CLOUD_AMT&" +
                "community=RE&longitude=%.2f&latitude=%.2f&start=%04d0101&end=%04d1231&format=JSON",
                lon, lat, startYear, endYear
        );

        List<SolarRecord> records = new ArrayList<>();

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "GridDB-SolarAnalytics/1.0 (Java-HttpClient)")
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("NASA POWER API returned HTTP {} for {}", response.statusCode(), regionName);
                return records;
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode params = root.path("properties").path("parameter");
            JsonNode ghiNode = params.path("ALLSKY_SFC_SW_DWN");
            JsonNode dniNode = params.path("ALLSKY_SFC_SW_DNI");
            JsonNode tempNode = params.path("T2M");
            JsonNode cloudNode = params.path("CLOUD_AMT");

            ghiNode.fieldNames().forEachRemaining(dateStr -> {
                int year = Integer.parseInt(dateStr.substring(0, 4));
                int month = Integer.parseInt(dateStr.substring(4, 6));
                int day = Integer.parseInt(dateStr.substring(6, 8));

                double rawGhi = ghiNode.path(dateStr).asDouble(-999.0);
                double rawDni = dniNode.path(dateStr).asDouble(-999.0);
                double rawTemp = tempNode.path(dateStr).asDouble(-999.0);
                double rawCloud = cloudNode.path(dateStr).asDouble(-999.0);

                // Sentinel cleanup
                double ghi = (rawGhi < 0) ? 3.5 : Math.round(rawGhi * 100.0) / 100.0;
                double dni = (rawDni < 0) ? ghi * 0.85 : Math.round(rawDni * 100.0) / 100.0;
                double temp = (rawTemp < -50) ? 25.0 : Math.round(rawTemp * 10.0) / 10.0;
                double cloudFraction = (rawCloud < 0) ? 0.20 : Math.round((rawCloud / 100.0) * 1000.0) / 1000.0;
                String season = MONTH_SEASONS.getOrDefault(month, "Summer");

                // Deterministic tie-breaker timestamp
                LocalDateTime ts = LocalDateTime.of(year, month, day, regionIndex, 0, 0)
                        .plusNanos((day * 1000L + regionIndex) * 1_000_000L);

                records.add(new SolarRecord(
                        ts, regionId, regionName, lat, lon,
                        ghi, dni, temp, cloudFraction, month, year, season
                ));
            });

            log.info("Loaded {} records for {} from NASA POWER API", records.size(), regionName);
        } catch (Exception e) {
            log.error("Error fetching NASA POWER data for {}: {}", regionName, e.getMessage());
        }

        return records;
    }
}

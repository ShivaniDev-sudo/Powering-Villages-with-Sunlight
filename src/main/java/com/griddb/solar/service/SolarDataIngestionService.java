package com.griddb.solar.service;

import com.griddb.solar.model.SolarRecord;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses real solar radiation records from CSV files (e.g., NASA POWER API downloads).
 */
@Service
public class SolarDataIngestionService {

    private static final Logger log = LoggerFactory.getLogger(SolarDataIngestionService.class);
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    /**
     * Parses a solar radiation CSV file and returns cleaned records.
     *
     * @param csvFile path to the CSV file
     * @return list of SolarRecord objects
     */
    public List<SolarRecord> load(Path csvFile) throws IOException {
        List<SolarRecord> records = new ArrayList<>();
        int skipped = 0;

        try (Reader reader = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreHeaderCase(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {

            for (CSVRecord row : parser) {
                try {
                    String tsStr = row.get("timestamp").trim();
                    LocalDateTime ts;
                    if (tsStr.endsWith("Z")) {
                        ts = LocalDateTime.parse(tsStr.substring(0, tsStr.length() - 1));
                    } else {
                        ts = LocalDateTime.parse(tsStr);
                    }

                    String regionId = row.get("regionId");
                    String regionName = row.get("regionName");
                    double lat = Double.parseDouble(row.get("latitude"));
                    double lon = Double.parseDouble(row.get("longitude"));
                    double ghi = Double.parseDouble(row.get("ghiKwhM2Day"));
                    double dni = Double.parseDouble(row.get("dniKwhM2Day"));
                    double temp = Double.parseDouble(row.get("temperatureCelsius"));
                    double cloud = Double.parseDouble(row.get("cloudCoverFraction"));
                    int month = Integer.parseInt(row.get("month"));
                    int year = Integer.parseInt(row.get("year"));
                    String season = row.get("season");

                    records.add(new SolarRecord(
                            ts, regionId, regionName, lat, lon,
                            ghi, dni, temp, cloud, month, year, season
                    ));
                } catch (Exception e) {
                    skipped++;
                    log.debug("Skipping row {}: {}", parser.getRecordNumber(), e.getMessage());
                }
            }
        }

        log.info("Loaded {} real solar records from {} ({} skipped)", records.size(), csvFile.getFileName(), skipped);
        return records;
    }
}

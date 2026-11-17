package com.griddb.solar.service;

import com.griddb.solar.model.SolarRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates realistic synthetic daily solar radiation data for multiple regions.
 *
 * <p>The generated data reproduces known solar resource patterns:
 * <ul>
 *   <li>Arid, low-latitude regions (Rajasthan, Gujarat) receive 5.5–7.0 kWh/m²/day annual average</li>
 *   <li>Higher-latitude or cloud-prone regions (Assam, Meghalaya) receive 3.5–4.5 kWh/m²/day</li>
 *   <li>Monsoon months (Jun–Sep) reduce GHI by 30–50% due to cloud cover</li>
 *   <li>Winter months have shorter days and lower solar elevation, reducing GHI 15–25%</li>
 *   <li>DNI (Direct Normal Irradiance) is more sensitive to cloud cover than GHI</li>
 * </ul>
 *
 * <p>Regions are modeled after real locations in India where rural electrification
 * via solar is a practical consideration.
 */
@Service
public class SyntheticSolarDataService {

    private static final Logger log = LoggerFactory.getLogger(SyntheticSolarDataService.class);

    // ── Region definitions ──────────────────────────────────────────────────
    // Each region: {id, name, lat, lon, baseGHI, baseDNI}
    // baseGHI = annual average GHI in kWh/m²/day under clear-sky conditions
    private static final Object[][] REGIONS = {
            {"RAJ", "Rajasthan (Jodhpur)",     26.29, 73.02, 6.2, 5.8},
            {"GUJ", "Gujarat (Kutch)",         23.73, 69.86, 5.9, 5.5},
            {"LAD", "Ladakh (Leh)",            34.15, 77.58, 6.5, 6.8},
            {"TN",  "Tamil Nadu (Ramanathapuram)", 9.37, 78.83, 5.6, 5.0},
            {"AP",  "Andhra Pradesh (Anantapur)", 14.68, 77.60, 5.5, 4.9},
            {"MP",  "Madhya Pradesh (Bhopal)", 23.26, 77.41, 5.3, 4.7},
            {"KAR", "Karnataka (Bellary)",     15.14, 76.92, 5.4, 4.8},
            {"MAH", "Maharashtra (Nashik)",    20.00, 73.78, 5.1, 4.5},
            {"UP",  "Uttar Pradesh (Jhansi)",  25.45, 78.57, 5.0, 4.4},
            {"ASM", "Assam (Guwahati)",        26.14, 91.74, 4.0, 3.2},
            {"WB",  "West Bengal (Purulia)",   23.33, 86.37, 4.6, 3.8},
            {"JK",  "Jammu & Kashmir (Jammu)", 32.73, 74.87, 4.8, 4.2},
    };

    // Monthly GHI multiplier (index 0 unused, 1=Jan .. 12=Dec)
    // Models solar elevation and day length at ~20–25°N latitude
    private static final double[] MONTHLY_GHI_FACTOR = {
            0, 0.82, 0.90, 1.02, 1.10, 1.12, 0.78, 0.65, 0.68, 0.82, 0.95, 0.88, 0.80
    };
    //      Jan   Feb   Mar   Apr   May   Jun   Jul   Aug   Sep   Oct   Nov   Dec
    // Jun–Aug reduced by monsoon cloud cover; Apr–May peak pre-monsoon

    // Cloud cover fraction by month (average baseline; varies by region)
    private static final double[] MONTHLY_CLOUD_BASE = {
            0, 0.15, 0.12, 0.15, 0.18, 0.22, 0.55, 0.70, 0.65, 0.45, 0.20, 0.15, 0.18
    };

    // Season assignment by month
    private static final String[] MONTH_SEASON = {
            "", "Winter", "Winter", "Summer", "Summer", "Summer",
            "Monsoon", "Monsoon", "Monsoon", "Post-Monsoon",
            "Post-Monsoon", "Winter", "Winter"
    };

    // Average temperature by month (°C, approximate for central India ~23°N)
    private static final double[] MONTHLY_TEMP_BASE = {
            0, 18.0, 21.0, 27.0, 33.0, 36.0, 34.0, 30.0, 29.0, 28.0, 26.0, 22.0, 18.0
    };

    /**
     * Generate daily solar radiation records for all regions across 2019–2023.
     * Produces ~21,900 rows (12 regions × 365 days × 5 years).
     */
    public List<SolarRecord> generate() {
        List<SolarRecord> all = new ArrayList<>();
        Random rng = new Random(42);

        int[] years = {2019, 2020, 2021, 2022, 2023};

        for (int year : years) {
            for (Object[] region : REGIONS) {
                all.addAll(generateRegionYear(region, year, rng));
            }
        }

        log.info("Total synthetic solar records generated: {}", all.size());
        return all;
    }

    // ─── private helpers ─────────────────────────────────────────────────────

    private List<SolarRecord> generateRegionYear(Object[] region, int year, Random rng) {
        String id = (String) region[0];
        String name = (String) region[1];
        double lat = (double) region[2];
        double lon = (double) region[3];
        double baseGHI = (double) region[4];
        double baseDNI = (double) region[5];

        List<SolarRecord> records = new ArrayList<>();

        // Latitude-based adjustment: higher latitudes have more seasonal variation
        double latFactor = 1.0 - Math.abs(lat - 23.0) * 0.008; // slight reduction away from tropic

        for (int month = 1; month <= 12; month++) {
            int daysInMonth = daysInMonth(month, year);
            double monthGhiFactor = MONTHLY_GHI_FACTOR[month];
            double monthCloudBase = MONTHLY_CLOUD_BASE[month];
            double monthTemp = MONTHLY_TEMP_BASE[month];
            String season = MONTH_SEASON[month];

            // Regional cloud cover adjustment:
            // Assam/WB get heavier monsoon; Rajasthan/Ladakh get lighter monsoon
            double regionalCloudAdj = 0;
            if (id.equals("ASM") || id.equals("WB")) {
                regionalCloudAdj = 0.10; // heavier monsoon
            } else if (id.equals("RAJ") || id.equals("LAD") || id.equals("GUJ")) {
                regionalCloudAdj = -0.08; // drier, less cloud
            }

            for (int day = 1; day <= daysInMonth; day++) {
                // Daily cloud cover with randomness
                double cloud = monthCloudBase + regionalCloudAdj
                        + (rng.nextGaussian() * 0.12);
                cloud = Math.max(0.0, Math.min(1.0, cloud));

                // GHI calculation
                double ghi = baseGHI * latFactor * monthGhiFactor
                        * (1.0 - cloud * 0.7) // clouds attenuate GHI
                        + rng.nextGaussian() * 0.3; // daily noise
                ghi = Math.max(0.5, Math.round(ghi * 100.0) / 100.0);

                // DNI is more affected by clouds than GHI
                double dni = baseDNI * latFactor * monthGhiFactor
                        * (1.0 - cloud * 0.85)
                        + rng.nextGaussian() * 0.35;
                dni = Math.max(0.2, Math.round(dni * 100.0) / 100.0);

                // Temperature with daily variation
                double temp = monthTemp
                        + (lat > 30 ? -4.0 : 0) // cooler at higher latitudes
                        + rng.nextGaussian() * 2.5;
                temp = Math.round(temp * 10.0) / 10.0;

                // Unique timestamp: each region gets a different hour offset to prevent collisions
                int regionIndex = getRegionIndex(id);
                LocalDateTime ts;
                try {
                    ts = LocalDateTime.of(year, month, day, regionIndex, 0, 0)
                            .plusNanos((day * 1000L + regionIndex) * 1_000_000L); // sub-second uniqueness
                } catch (Exception e) {
                    continue;
                }

                records.add(new SolarRecord(
                        ts, id, name, lat, lon,
                        ghi, dni, temp,
                        Math.round(cloud * 1000.0) / 1000.0,
                        month, year, season
                ));
            }
        }
        return records;
    }

    private static int getRegionIndex(String regionId) {
        for (int i = 0; i < REGIONS.length; i++) {
            if (REGIONS[i][0].equals(regionId)) return i;
        }
        return 0;
    }

    private static int daysInMonth(int month, int year) {
        return switch (month) {
            case 2 -> (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) ? 29 : 28;
            case 4, 6, 9, 11 -> 30;
            default -> 31;
        };
    }
}

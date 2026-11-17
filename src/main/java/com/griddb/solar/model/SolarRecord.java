package com.griddb.solar.model;

import java.time.LocalDateTime;

/**
 * Represents a single daily solar radiation measurement for a specific region.
 * Maps directly to a row in the GridDB time-series container.
 *
 * <p>GHI (Global Horizontal Irradiance) is the total solar radiation received
 * on a horizontal surface, measured in kWh/m²/day. It is the standard metric
 * for assessing photovoltaic energy potential at a given location.
 */
public record SolarRecord(
        LocalDateTime timestamp,        // Date of the measurement (unique per region per day)
        String regionId,                 // Short region code (e.g., "RAJ", "GUJ")
        String regionName,               // Human-readable region name
        double latitude,                 // Measurement site latitude
        double longitude,                // Measurement site longitude
        double ghiKwhM2Day,              // Global Horizontal Irradiance (kWh/m²/day)
        double dniKwhM2Day,              // Direct Normal Irradiance (kWh/m²/day)
        double temperatureCelsius,       // Ambient temperature at measurement time
        double cloudCoverFraction,       // 0.0 (clear) to 1.0 (overcast)
        int month,                       // Calendar month 1–12
        int year,                        // Calendar year
        String season                    // Season label: Summer, Monsoon, Post-Monsoon, Winter
) {}

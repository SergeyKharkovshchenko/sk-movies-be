package com.moviesApp.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the earthquake data marts (dm schema) that an external pipeline loads into the same
 * PostgreSQL database as this app: dm.fct_count_day_earthquake (date, count_earthquake) and
 * dm.fct_avg_day_earthquake (date, avg_mag). Read-only -- the pipeline owns these tables.
 */
@Service
public class EarthquakeService {

    // Ten years of daily rows (~3.7k) is still a light payload, and the marts already hold more
    // than one year -- a one-year cap made the FE's "All" preset fail. Wider is almost certainly a typo.
    private static final long MAX_RANGE_DAYS = 3660;

    private final JdbcTemplate jdbc;

    public EarthquakeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** First and last date present in either mart -- the FE uses it to bound its date inputs. */
    public Map<String, Object> range() {
        return jdbc.queryForObject(
                "SELECT min(date) AS min_date, max(date) AS max_date FROM (" +
                "  SELECT date FROM dm.fct_count_day_earthquake " +
                "  UNION SELECT date FROM dm.fct_avg_day_earthquake) d",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    Date min = rs.getDate("min_date"), max = rs.getDate("max_date");
                    m.put("minDate", min != null ? min.toLocalDate().toString() : null);
                    m.put("maxDate", max != null ? max.toLocalDate().toString() : null);
                    return m;
                });
    }

    /**
     * One row per date in [from, to] that either mart has, joined on date. A day can be present in
     * one mart but not the other (they're loaded separately), so missing values come back as null
     * rather than 0 -- "no data" and "zero earthquakes" must stay distinguishable.
     */
    public List<Map<String, Object>> daily(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new IllegalArgumentException("'to' must not be before 'from'");
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("date range is limited to " + MAX_RANGE_DAYS + " days");
        }
        return jdbc.query(
                "SELECT coalesce(c.date, a.date) AS date, c.count_earthquake, a.avg_mag " +
                "FROM (SELECT * FROM dm.fct_count_day_earthquake WHERE date BETWEEN ? AND ?) c " +
                "FULL OUTER JOIN (SELECT * FROM dm.fct_avg_day_earthquake WHERE date BETWEEN ? AND ?) a " +
                "  ON a.date = c.date " +
                "ORDER BY 1",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("date", rs.getDate("date").toLocalDate().toString());
                    long count = rs.getLong("count_earthquake");
                    row.put("count", rs.wasNull() ? null : count);
                    double avg = rs.getDouble("avg_mag");
                    row.put("avgMag", rs.wasNull() ? null : avg);
                    return row;
                },
                Date.valueOf(from), Date.valueOf(to), Date.valueOf(from), Date.valueOf(to));
    }
}

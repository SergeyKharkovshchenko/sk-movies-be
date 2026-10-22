package com.moviesApp.controllers;

import com.moviesApp.service.EarthquakeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/earthquakes")
public class EarthquakeController {

    private final EarthquakeService earthquakeService;

    public EarthquakeController(EarthquakeService earthquakeService) {
        this.earthquakeService = earthquakeService;
    }

    /** { minDate, maxDate } covered by the data marts (ISO dates, null when empty). */
    @GetMapping("/range")
    public ResponseEntity<Map<String, Object>> range() {
        try {
            return ResponseEntity.ok(earthquakeService.range());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /**
     * Daily earthquake count and average magnitude from the dm data marts.
     * GET /earthquakes/daily?from=2025-05-01&to=2025-05-28
     * Returns: { from, to, days: [{ date, count, avgMag }] } -- count/avgMag are null for a day
     * only one of the two marts has.
     */
    @GetMapping("/daily")
    public ResponseEntity<Map<String, Object>> daily(@RequestParam String from, @RequestParam String to) {
        LocalDate fromDate, toDate;
        try {
            fromDate = LocalDate.parse(from);
            toDate = LocalDate.parse(to);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "from/to must be ISO dates (YYYY-MM-DD)"));
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("from", fromDate.toString());
            body.put("to", toDate.toString());
            body.put("days", earthquakeService.daily(fromDate, toDate));
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }
}

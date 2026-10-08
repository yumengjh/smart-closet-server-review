package com.smartcloset.weather;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Daily forecast for the user's own city (design 5.12, D5). Free Open-Meteo endpoints, no key, no model calls.
 * Only the city name the user typed in settings is sent; results are cached (forecast 1 h, place name for the process,
 * failures 10 min) and every failure degrades to "no weather" so recommendations fall back to the date's season.
 */
@Service
public class OpenMeteoWeather {
    public record Daily(String place, LocalDate day, double minC, double maxC, Double precipitationMm, double temperatureC, Instant fetchedAt) {
        public String label() { return place + " " + Math.round(minC) + "~" + Math.round(maxC) + "°C"; }
    }
    private record Place(String name, double lat, double lon, String timezone) {}
    private record Cached<T>(Optional<T> value, long atMs) {}
    static final Duration FORECAST_TTL = Duration.ofHours(1), FAILURE_TTL = Duration.ofMinutes(10);

    private final boolean enabled;
    private final String geocodeUrl, forecastUrl;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Map<String, Cached<Place>> places = new ConcurrentHashMap<>();
    private final Map<String, Cached<Daily>> forecasts = new ConcurrentHashMap<>();

    public OpenMeteoWeather(@Value("${closet.weather.enabled:true}") boolean enabled,
                            @Value("${closet.weather.geocode-url:https://geocoding-api.open-meteo.com/v1/search}") String geocodeUrl,
                            @Value("${closet.weather.forecast-url:https://api.open-meteo.com/v1/forecast}") String forecastUrl,
                            @Value("${closet.weather.timeout-seconds:3}") int timeoutSeconds, ObjectMapper json) {
        this.enabled = enabled; this.geocodeUrl = geocodeUrl; this.forecastUrl = forecastUrl; this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
    }

    public boolean enabled() { return enabled; }

    public Optional<Daily> forecast(String city, LocalDate day) {
        if (!enabled || city == null || city.isBlank()) return Optional.empty();
        String key = city.strip() + "|" + day;
        long now = System.currentTimeMillis();
        var hit = forecasts.get(key);
        if (hit != null && now - hit.atMs() < (hit.value().isPresent() ? FORECAST_TTL : FAILURE_TTL).toMillis()) return hit.value();
        Optional<Daily> value;
        try { var p = place(city.strip()); value = p.isPresent() ? fetch(p.get(), day) : Optional.empty(); }
        catch (Exception e) { LoggerFactory.getLogger(getClass()).info("weather unavailable for configured city: {}", e.getClass().getSimpleName()); value = Optional.empty(); }
        if (forecasts.size() > 500) forecasts.clear();
        forecasts.put(key, new Cached<>(value, now));
        return value;
    }

    private Optional<Place> place(String city) throws Exception {
        long now = System.currentTimeMillis();
        var hit = places.get(city);
        if (hit != null && (hit.value().isPresent() || now - hit.atMs() < FAILURE_TTL.toMillis())) return hit.value();
        var root = get(geocodeUrl + "?count=1&language=zh&format=json&name=" + URLEncoder.encode(city, StandardCharsets.UTF_8));
        var r = root.path("results");
        Optional<Place> value = r.isArray() && !r.isEmpty()
            ? Optional.of(new Place(r.get(0).path("name").asText(city), r.get(0).path("latitude").asDouble(), r.get(0).path("longitude").asDouble(), r.get(0).path("timezone").asText("auto")))
            : Optional.empty();
        if (places.size() > 200) places.clear();
        places.put(city, new Cached<>(value, now));
        return value;
    }

    private Optional<Daily> fetch(Place p, LocalDate day) throws Exception {
        var root = get(forecastUrl + "?latitude=" + p.lat() + "&longitude=" + p.lon() + "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum&timezone="
            + URLEncoder.encode(p.timezone(), StandardCharsets.UTF_8) + "&start_date=" + day + "&end_date=" + day);
        var daily = root.path("daily");
        var max = daily.path("temperature_2m_max").path(0); var min = daily.path("temperature_2m_min").path(0);
        if (!max.isNumber() || !min.isNumber()) return Optional.empty();
        double hi = max.asDouble(), lo = min.asDouble();
        var rain = daily.path("precipitation_sum").path(0);
        // Daytime feel: closer to the high than the low, because outfits are chosen for the day.
        double t = Math.round((0.6 * hi + 0.4 * lo) * 10) / 10.0;
        return Optional.of(new Daily(p.name(), day, lo, hi, rain.isNumber() ? rain.asDouble() : null, t, Instant.now()));
    }

    private JsonNode get(String url) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
        return json.readTree(response.body());
    }
}

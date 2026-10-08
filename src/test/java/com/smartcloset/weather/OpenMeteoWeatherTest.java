package com.smartcloset.weather;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

class OpenMeteoWeatherTest {
    static HttpServer server; static final AtomicInteger geocodes = new AtomicInteger(), forecasts = new AtomicInteger();
    static volatile int forecastStatus = 200;

    @BeforeAll static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/geo", ex -> {
            geocodes.incrementAndGet();
            String q = ex.getRequestURI().getRawQuery();
            byte[] body = (q.contains("%E6%9D%AD%E5%B7%9E") ? "{\"results\":[{\"name\":\"杭州\",\"latitude\":30.29,\"longitude\":120.16,\"timezone\":\"Asia/Shanghai\"}]}" : "{}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length); ex.getResponseBody().write(body); ex.close();
        });
        server.createContext("/forecast", ex -> {
            forecasts.incrementAndGet();
            byte[] body = "{\"daily\":{\"temperature_2m_max\":[18.0],\"temperature_2m_min\":[8.0],\"precipitation_sum\":[1.2]}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(forecastStatus, forecastStatus == 200 ? body.length : -1); if (forecastStatus == 200) ex.getResponseBody().write(body); ex.close();
        });
        server.start();
    }
    @AfterAll static void stop() { server.stop(0); }

    private OpenMeteoWeather weather(boolean enabled) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new OpenMeteoWeather(enabled, base + "/geo", base + "/forecast", 2, JsonMapper.builder().build());
    }

    @Test void daytimeTemperatureLeansToTheHighAndIsCached() {
        var w = weather(true); int before = forecasts.get();
        var d = w.forecast("杭州", LocalDate.of(2026, 10, 9)).orElseThrow();
        assertEquals(14.0, d.temperatureC());
        assertEquals("杭州 8~18°C", d.label());
        w.forecast("杭州", LocalDate.of(2026, 10, 9));
        assertEquals(before + 1, forecasts.get(), "second call within an hour comes from the cache");
    }

    @Test void unknownCityDisabledServiceAndServerErrorsAllDegradeToNoWeather() {
        assertTrue(weather(true).forecast("不存在的城市", LocalDate.of(2026, 10, 9)).isEmpty());
        assertTrue(weather(false).forecast("杭州", LocalDate.of(2026, 10, 9)).isEmpty());
        assertTrue(weather(true).forecast(" ", LocalDate.of(2026, 10, 9)).isEmpty());
        forecastStatus = 500;
        try { assertTrue(weather(true).forecast("杭州", LocalDate.of(2026, 10, 10)).isEmpty()); } finally { forecastStatus = 200; }
    }

    @Test void weatherTemperatureDrivesTheIntentSeasonUnlessTheUserNamedOne() {
        var intent = new com.smartcloset.recommend.Intent(com.smartcloset.recommend.Intent.Occasion.DAILY, null, null, null, java.util.List.of(), java.util.List.of(), java.util.List.of(), "", java.util.List.of(), java.util.List.of(), 3, java.util.Map.of())
            .normalized(java.time.Clock.fixed(java.time.Instant.parse("2026-07-08T02:00:00Z"), java.time.ZoneOffset.UTC));
        assertEquals("夏", intent.season());
        var cooled = intent.withWeather(6, "杭州 2~9°C");
        assertEquals(6.0, cooled.temperatureC());
        assertEquals("冬", cooled.season());
        assertEquals("WEATHER", cooled.sources().get("temperatureC"));
        assertTrue(cooled.describe().contains("6°C（杭州 2~9°C）"), cooled.describe());
    }
}

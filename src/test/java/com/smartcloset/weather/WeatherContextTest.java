package com.smartcloset.weather;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WeatherContextTest {
 @Test void freshnessDistinguishesCurrentForecastMissingAndFutureData(){
  Instant now=Instant.parse("2026-10-07T01:00:00Z");var q=new WeatherProvider.Query(new WeatherProvider.Place("user-selected","用户选择城市",ZoneId.of("Asia/Shanghai")),LocalDate.of(2026,10,7),now,now.plusSeconds(3600));
  var w=new WeatherProvider.Weather("fixture",q,now.minusSeconds(1200),15d,12d,2d,4d,"AVAILABLE",List.of());
  assertFalse(WeatherContext.fresh(w,now,false));assertTrue(WeatherContext.fresh(w,now,true));assertEquals("STALE",WeatherContext.conditions(w,now,false).get("availability"));assertEquals("UNAVAILABLE",WeatherContext.conditions(null,now,false).get("availability"));
  assertFalse(WeatherContext.fresh(new WeatherProvider.Weather("fixture",q,now.plusSeconds(1),null,null,null,null,"AVAILABLE",List.of()),now,false));assertEquals(15,WeatherContext.ttl(false).toMinutes());assertEquals(60,WeatherContext.ttl(true).toMinutes());
 }
}

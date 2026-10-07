package com.smartcloset.weather;
import java.time.*;
import java.util.*;
/** Deterministic freshness/condition adapter; no network or model calls. */
public final class WeatherContext {
 public static Duration ttl(boolean forecast){return Duration.ofMinutes(forecast?60:15);}
 public static boolean fresh(WeatherProvider.Weather weather,Instant now,boolean forecast){return weather!=null&&"AVAILABLE".equals(weather.availability())&&weather.fetchedAt()!=null&&!weather.fetchedAt().isAfter(now)&&weather.fetchedAt().plus(ttl(forecast)).isAfter(now);}
 public static Map<String,Object> conditions(WeatherProvider.Weather weather,Instant now,boolean forecast){if(weather==null)return Map.of("availability","UNAVAILABLE","note","天气未接入，请提供地点、日期和冷暖条件。");var result=new LinkedHashMap<String,Object>();result.put("availability",!"AVAILABLE".equals(weather.availability())?weather.availability():fresh(weather,now,forecast)?"AVAILABLE":"STALE");result.put("weather",weather);result.put("note","保暖和防水能力依据已核对衣物资料，未知材质不能作保证。");return Collections.unmodifiableMap(result);}
 private WeatherContext() {}
}

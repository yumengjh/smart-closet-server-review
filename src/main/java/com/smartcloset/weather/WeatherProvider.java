package com.smartcloset.weather;
import java.time.*;
import java.util.*;
/** Future provider adapter. Coordinates/city are explicitly selected by the user, never inferred. */
public interface WeatherProvider {
 record Place(String id,String label,ZoneId timezone) {}
 record Query(Place place,LocalDate date,Instant startsAt,Instant endsAt) {}
 record Weather(String source,Query query,Instant fetchedAt,Double temperatureC,Double feelsLikeC,Double precipitationMm,Double windMetersPerSecond,String availability,List<String> limitations) {}
 Weather fetch(Query query);
}

package com.smartcloset.api;

import java.time.Duration;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Bundles under /assets/ carry a content hash in their file name, so browsers may keep them for a year without asking
 * again. index.html and the other unhashed files keep the default revalidation, so a release still shows at once.
 */
@Configuration
public class StaticAssetCaching implements WebMvcConfigurer {
    private final String[] locations;
    public StaticAssetCaching(@Value("${spring.web.resources.static-locations:classpath:/static/}") String[] locations) {
        this.locations = Arrays.stream(locations).map(l -> (l.endsWith("/") ? l : l + "/") + "assets/").toArray(String[]::new);
    }
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**").addResourceLocations(locations)
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
    }
}

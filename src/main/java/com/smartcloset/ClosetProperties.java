package com.smartcloset;

import java.nio.file.Path;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("closet")
public record ClosetProperties(@NotNull Path storageDir, @NotNull Path modelPath,
                               @Min(1024) long maxPixels, @Min(64) int grabcutMaxSide,
                               @Min(1) int inferenceThreads) {}

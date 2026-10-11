package com.smartcloset.storage;

import com.smartcloset.ClosetProperties;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {
    private static final Logger log = LoggerFactory.getLogger(StorageConfiguration.class);

    @Bean StorageSettings storageSettings(Environment env) { return StorageSettings.of(env); }

    @Bean BlobStore blobStore(StorageSettings settings, ClosetProperties closet) throws IOException {
        if (!settings.s3()) return new LocalBlobStore(closet.storageDir());
        var store = new S3BlobStore(settings);
        // A wrong bucket, region or key must stop the start, not surface at the first upload.
        store.probe();
        log.info("Images are stored in bucket {} at {} (prefix '{}', serve={})", settings.bucket(), settings.endpoint(), settings.prefix(), settings.serve());
        return store;
    }
}

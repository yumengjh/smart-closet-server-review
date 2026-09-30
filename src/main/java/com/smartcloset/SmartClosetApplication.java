package com.smartcloset;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ClosetProperties.class, com.smartcloset.generation.GenerationProperties.class, com.smartcloset.recognition.RecognitionProperties.class})
public class SmartClosetApplication {
    public static void main(String[] args) {
        if (com.smartcloset.security.ClosetAccountCli.isCommand(args)) {
            com.smartcloset.security.ClosetAccountCli.run(args);
            return;
        }
        SpringApplication.run(SmartClosetApplication.class, args);
    }
}

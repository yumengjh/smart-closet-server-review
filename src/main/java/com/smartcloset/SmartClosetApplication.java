package com.smartcloset;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ClosetProperties.class, com.smartcloset.recognition.RecognitionProperties.class, com.smartcloset.agent.AgentProperties.class})
public class SmartClosetApplication {
    public static void main(String[] args) {
        if (com.smartcloset.storage.StorageMigration.isCommand(args)) {
            // UTF-8 whatever the host locale is: containers usually have none and would print "?" for Chinese.
            var out = new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, java.nio.charset.StandardCharsets.UTF_8);
            System.exit(com.smartcloset.storage.StorageMigration.run(args, System.getenv(), out));
        }
        if (com.smartcloset.security.ClosetAccountCli.isCommand(args)) {
            com.smartcloset.security.ClosetAccountCli.run(args);
            return;
        }
        SpringApplication.run(SmartClosetApplication.class, args);
    }
}

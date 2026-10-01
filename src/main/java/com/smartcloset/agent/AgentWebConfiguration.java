package com.smartcloset.agent;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AgentWebConfiguration implements WebMvcConfigurer {
    private final AgentProperties properties;
    public AgentWebConfiguration(AgentProperties properties) { this.properties=properties; }
    @Override public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(properties.timeoutSeconds()*1000L+10000);
    }
}

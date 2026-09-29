package com.agentreleaselab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.agentreleaselab.domain")
public class AgentReleaseLabApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentReleaseLabApplication.class, args);
    }
}

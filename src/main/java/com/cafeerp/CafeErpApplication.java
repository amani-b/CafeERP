package com.cafeerp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// Enables the @Scheduled assistant-conversation auto-archival job
// (see AssistantConversationArchivalJob).
@EnableScheduling
public class CafeErpApplication {

    public static void main(String[] args) {
        SpringApplication.run(CafeErpApplication.class, args);
    }
}

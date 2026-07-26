package com.shitulelv.aicollab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AiCollabBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiCollabBackendApplication.class, args);
    }

}

package com.shitulelv.aicollab;

import org.springframework.boot.SpringApplication;

public class TestAiCollabBackendApplication {

    public static void main(String[] args) {
        SpringApplication.from(AiCollabBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}

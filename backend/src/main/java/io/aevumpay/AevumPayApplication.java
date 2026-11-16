package io.aevumpay;

import io.aevumpay.config.AevumProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AevumProperties.class)
public class AevumPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(AevumPayApplication.class, args);
    }
}

package com.sparrow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SparrowApplication {

    public static void main(String[] args) {
        SpringApplication.run(SparrowApplication.class, args);
    }
}

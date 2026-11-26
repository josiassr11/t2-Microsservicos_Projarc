package com.pucrs.publicadormoodle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PublicadorMoodleApplication {

    public static void main(String[] args) {
        SpringApplication.run(PublicadorMoodleApplication.class, args);
    }
}

package com.pucrs.monitorieducar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MonitorIeducarApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonitorIeducarApplication.class, args);
    }
}

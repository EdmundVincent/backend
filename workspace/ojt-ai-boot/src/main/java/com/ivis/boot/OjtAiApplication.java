package com.ivis.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"com.ivis"}) // Scan all our components and starters
public class OjtAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(OjtAiApplication.class, args);
    }
}

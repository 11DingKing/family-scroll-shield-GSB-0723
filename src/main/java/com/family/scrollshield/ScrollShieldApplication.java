package com.family.scrollshield;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ScrollShieldApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScrollShieldApplication.class, args);
    }
}

package com.newscloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NewsCloudApplication {
    public static void main(String[] args) {
        SpringApplication.run(NewsCloudApplication.class, args);
    }
}

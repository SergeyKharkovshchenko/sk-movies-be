package com.moviesApp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication()
@ComponentScan(basePackages = {"com.moviesApp", "com.cache"})
public class skMoviesApp {
    public static void main(String[] args) {
        SpringApplication.run(skMoviesApp.class, args);

    }
}

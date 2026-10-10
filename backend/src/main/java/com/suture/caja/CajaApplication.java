package com.suture.caja;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CajaApplication {
    public static void main(String[] args) { SpringApplication.run(CajaApplication.class, args); }
}

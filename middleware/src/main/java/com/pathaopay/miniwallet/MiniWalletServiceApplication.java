package com.pathaopay.miniwallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Entry point; scheduling is enabled for the add-money outbox dispatcher. */
@SpringBootApplication
@EnableScheduling
public class MiniWalletServiceApplication {

    static void main(String[] args) {
        SpringApplication.run(MiniWalletServiceApplication.class, args);
    }

}

package com.bill.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.CommandLineRunner;

@SpringBootApplication
public class InventoryApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }

    @Bean
    CommandLineRunner seedInventory(InventoryRepository inventory) {
        return args -> {
            if (!inventory.existsById("LIFE-DEMO-001")) {
                inventory.save(new InventoryItem("LIFE-DEMO-001", 100));
            }
            if (!inventory.existsById("LIFE-CONCURRENCY-001")) {
                inventory.save(new InventoryItem("LIFE-CONCURRENCY-001", 1));
            }
            if (!inventory.existsById("LIFE-LOAD-001")) {
                inventory.save(new InventoryItem("LIFE-LOAD-001", 100_000));
            }
        };
    }
}

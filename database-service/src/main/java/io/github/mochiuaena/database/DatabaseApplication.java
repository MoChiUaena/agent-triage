package io.github.mochiuaena.database;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class DatabaseApplication {
    public static void main(String[] args) { SpringApplication.run(DatabaseApplication.class, args); }
}

package uk.ac.ed.inf.infraq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class InfraQApplication {
    public static void main(String[] args) {
        SpringApplication.run(InfraQApplication.class, args);
    }
}

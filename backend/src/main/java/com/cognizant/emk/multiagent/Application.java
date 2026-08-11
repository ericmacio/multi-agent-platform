package com.cognizant.emk.multiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;

/**
 * Multi-Agent Platform backend entry point.
 *
 * <p>EPIC-02 wires PostgreSQL + JPA + Flyway. {@link EntityScan} narrows the entity scan
 * to the persistence sub-package so Hibernate does not attempt to interpret unrelated
 * packages (notably {@code com.cognizant.emk.multiagent.application}) as candidate
 * classes. JPA repositories are picked up by Spring Boot auto-configuration from
 * {@code infrastructure.persistence.springdata} (default scan from this class's package).
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.cognizant.emk.multiagent.infrastructure.config")
@EntityScan("com.cognizant.emk.multiagent.infrastructure.persistence.entity")
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}

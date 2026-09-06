package com.babelflux.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BabelFluxApplication {
    public static void main(String[] args) {
        SpringApplication.run(BabelFluxApplication.class, args);
    }
}

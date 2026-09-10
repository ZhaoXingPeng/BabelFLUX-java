package com.babelflux.backend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@MapperScan("com.babelflux.backend.infrastructure.mybatis")
public class BabelFluxApplication {
    public static void main(String[] args) {
        SpringApplication.run(BabelFluxApplication.class, args);
    }
}

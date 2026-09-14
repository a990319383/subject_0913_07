package com.evops;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@MapperScan("com.evops.mapper")
@EnableTransactionManagement(proxyTargetClass = true)
public class EvopsApplication {
    public static void main(String[] args) {
        SpringApplication.run(EvopsApplication.class, args);
    }
}

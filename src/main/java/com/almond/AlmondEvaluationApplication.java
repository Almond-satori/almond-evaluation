package com.almond;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.almond.mapper")
@SpringBootApplication
public class AlmondEvaluationApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlmondEvaluationApplication.class, args);
    }

}

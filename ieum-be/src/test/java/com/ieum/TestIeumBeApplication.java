package com.ieum;

import org.springframework.boot.SpringApplication;

/** 로컬 실행: ./gradlew bootTestRun → Docker로 Postgres를 띄워서 붙는다 (.env의 DB 설정 무시). */
public class TestIeumBeApplication {

    public static void main(String[] args) {
        SpringApplication.from(IeumBeApplication::main).with(TestcontainersConfig.class).run(args);
    }
}

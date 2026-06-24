package com.sparrow.chain;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDiscoveryClient
@EnableCaching   // 产业链只读接口的进程内缓存(读多写少)
@EnableScheduling // 周期清空产业链缓存,吸收爬虫同步更新
@MapperScan("com.sparrow.chain.infrastructure.persistence")
@ComponentScan({"com.sparrow.chain", "com.sparrow.common"})
public class ChainApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChainApplication.class, args);
    }
}

package com.sparrow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// 不用 @MapperScan 全包扫描:它会把普通边界接口(如 TechNodeCatalog)也代理成 mapper,
// 注入时盖过真实现。真正的 mapper 均带 @Mapper 注解,由 starter 自动发现。
@SpringBootApplication
@ConfigurationPropertiesScan
public class SparrowApplication {

    public static void main(String[] args) {
        SpringApplication.run(SparrowApplication.class, args);
    }
}

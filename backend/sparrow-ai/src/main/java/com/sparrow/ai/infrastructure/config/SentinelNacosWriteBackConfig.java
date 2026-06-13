package com.sparrow.ai.infrastructure.config;

import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.csp.sentinel.datasource.WritableDataSource;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.transport.util.WritableDataSourceRegistry;
import com.alibaba.fastjson.JSON;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 注册 Sentinel flow 规则的 Nacos 可写数据源。
 * 启用后,Dashboard push 的规则会经 transport 写回 Nacos(与 NacosDataSource 读同一 dataId),
 * 实现 "Dashboard 改规则 → Nacos 持久化 → 重启/多实例仍生效" 的闭环。
 * 默认关闭(本地无 Nacos 时不注册);docker 通过 SPARROW_SENTINEL_WRITE_BACK=true 打开。
 */
@Configuration
@ConditionalOnProperty(name = "sparrow.sentinel.write-back", havingValue = "true")
public class SentinelNacosWriteBackConfig {

    private static final Logger log = LoggerFactory.getLogger(SentinelNacosWriteBackConfig.class);

    private final String serverAddr;
    private final String dataId;
    private final String group;

    public SentinelNacosWriteBackConfig(
            @Value("${spring.cloud.sentinel.datasource.flow.nacos.server-addr:${NACOS_ADDR:localhost:8848}}") String serverAddr,
            @Value("${spring.cloud.sentinel.datasource.flow.nacos.data-id:sparrow-ai-flow-rules}") String dataId,
            @Value("${spring.cloud.sentinel.datasource.flow.nacos.group-id:SENTINEL_GROUP}") String group) {
        this.serverAddr = serverAddr;
        this.dataId = dataId;
        this.group = group;
    }

    @PostConstruct
    public void registerWritableDataSource() throws Exception {
        ConfigService configService = NacosFactory.createConfigService(serverAddr);
        Converter<List<FlowRule>, String> encoder = JSON::toJSONString;
        WritableDataSource<List<FlowRule>> writableDataSource =
                new NacosWritableDataSource<>(configService, dataId, group, encoder);
        WritableDataSourceRegistry.registerFlowDataSource(writableDataSource);
        log.info("Sentinel 规则写回 Nacos 已注册: dataId={} group={} server={}", dataId, group, serverAddr);
    }
}

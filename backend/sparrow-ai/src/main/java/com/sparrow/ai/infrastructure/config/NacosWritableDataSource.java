package com.sparrow.ai.infrastructure.config;

import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.csp.sentinel.datasource.WritableDataSource;
import com.alibaba.nacos.api.config.ConfigService;

/**
 * Sentinel 规则写回 Nacos 的可写数据源。
 * 当 Dashboard 通过 transport 把规则 push 到本应用时,Sentinel 的 ModifyRulesCommandHandler
 * 会调用已注册的 WritableDataSource.write(),从而把规则持久化回 Nacos(与读数据源同一 dataId)。
 */
public class NacosWritableDataSource<T> implements WritableDataSource<T> {

    private final ConfigService configService;
    private final String dataId;
    private final String group;
    private final Converter<T, String> encoder;

    public NacosWritableDataSource(ConfigService configService, String dataId, String group,
                                   Converter<T, String> encoder) {
        this.configService = configService;
        this.dataId = dataId;
        this.group = group;
        this.encoder = encoder;
    }

    @Override
    public void write(T value) throws Exception {
        configService.publishConfig(dataId, group, encoder.convert(value));
    }

    @Override
    public void close() {
        // ConfigService 由 Nacos 管理,无需在此关闭
    }
}

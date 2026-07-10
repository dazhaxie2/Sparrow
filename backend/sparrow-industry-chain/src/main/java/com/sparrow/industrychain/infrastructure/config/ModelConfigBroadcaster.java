package com.sparrow.industrychain.infrastructure.config;

import com.sparrow.industrychain.application.ModelConfigService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * 模型配置变更的多实例广播(Redis Pub/Sub)。
 *
 * <p>管理端在某实例激活配置后,向 {@link #CHANNEL} 发布一条消息,
 * 所有实例(含发布者自身之外)收到后重新加载激活配置并 {@link ChatModelProvider#swap} 原子切换。
 * 这样多实例部署无需 Kafka,且各实例本地无状态。
 */
@Component
public class ModelConfigBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigBroadcaster.class);

    /** 广播频道。 */
    public static final String CHANNEL = "sparrow:model-config:reload";

    private final StringRedisTemplate redis;
    private final RedisConnectionFactory connectionFactory;
    private final ModelConfigService modelConfigService;

    public ModelConfigBroadcaster(StringRedisTemplate redis, RedisConnectionFactory connectionFactory,
                                  @Lazy ModelConfigService modelConfigService) {
        this.redis = redis;
        this.connectionFactory = connectionFactory;
        this.modelConfigService = modelConfigService;
    }

    @PostConstruct
    public void subscribe() {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(new ReloadListener(), new ChannelTopic(CHANNEL));
        container.afterPropertiesSet();
        container.start();
        log.info("已订阅模型配置变更广播频道: {}", CHANNEL);
    }

    /** 发布一次「重新加载」消息,供其他实例热切换。 */
    public void publishReload() {
        try {
            redis.convertAndSend(CHANNEL, String.valueOf(System.currentTimeMillis()));
        } catch (Exception e) {
            log.warn("广播模型配置变更失败(本实例已切换): {}", e.getMessage());
        }
    }

    /** 收到广播:重新加载激活配置并原子切换模型。 */
    void reloadActiveLocally() {
        modelConfigService.applyActiveConfig();
    }

    private class ReloadListener implements MessageListener {
        @Override
        public void onMessage(Message message, byte[] pattern) {
            log.info("收到模型配置变更广播,重新加载激活配置");
            try {
                reloadActiveLocally();
            } catch (Exception e) {
                log.error("处理模型配置变更广播失败: {}", e.getMessage(), e);
            }
        }
    }
}

package com.shitulelv.aicollab.system.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * 集群多实例配置变更监听器。
 */
@Component
public class SystemConfigSync implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(SystemConfigSync.class);
    private final SystemConfigService service;

    public SystemConfigSync(SystemConfigService service) {
        this.service = service;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            log.info("收到集群配置变更广播，正在重新同步本地运行时配置...");
            service.reloadFromDatabase();
        } catch (Exception e) {
            log.error("集群配置变更同步失败: {}", e.getMessage());
        }
    }
}

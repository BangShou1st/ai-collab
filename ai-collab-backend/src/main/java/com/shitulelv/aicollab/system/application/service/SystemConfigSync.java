package com.shitulelv.aicollab.system.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.UUID;

/**
 * 集群多实例配置变更 Stream 消费者（Redis 深化 P2：Pub/Sub 监听器 → Stream 消费者组）。
 *
 * <p><b>消费语义设计（ACK / MAXLEN / 周期对账分工）</b>：</p>
 * <ul>
 *   <li>每个实例拥有<b>独立的消费者组</b>（组名 = 实例标识，见 {@link #resolveGroupName()}）。
 *       消费者组内多消费者是"负载均衡"（消息只投给组内一个消费者）而非广播，因此
 *       "每实例一份组"是实现"每实例都收到全量消息"的正确姿势；</li>
 *   <li><b>处理成功才 XACK</b>：处理失败/实例宕机时消息留在 PEL 中。<b>有意不实现
 *       PEL 补偿重投（无 XAUTOCLAIM 循环）</b> —— 广播消息内容只是版本号且接收方回源
 *       数据库，错过/滞留的消息由 MAXLEN ~100 短历史补读 + 周期对账
 *       （{@link SystemConfigService#scheduledSync()}）最终收敛，避免为广播语义堆
 *       精确投递机制；</li>
 *   <li><b>版本单调防护</b>：委托 {@link SystemConfigService#syncFromBroadcast(long)}，
 *       低于等于本地版本的旧消息（历史回放/重复投递/自回声）直接忽略，消费天然幂等。</li>
 * </ul>
 */
@Component
public class SystemConfigSync implements StreamListener<String, MapRecord<String, String, String>> {
    private static final Logger log = LoggerFactory.getLogger(SystemConfigSync.class);
    private static final String GROUP_PREFIX = "cfg-sync-";
    /** 组内单一消费者名：每组（每实例）只有一个消费者，名字无区分意义。 */
    public static final String CONSUMER_NAME = "main";

    private final SystemConfigService service;
    private final StringRedisTemplate redisTemplate;
    private final String groupName;

    public SystemConfigSync(SystemConfigService service,
                            StringRedisTemplate redisTemplate,
                            @Value("${server.port:8080}") int serverPort) {
        this.service = service;
        this.redisTemplate = redisTemplate;
        this.groupName = resolveGroupName(serverPort);
    }

    /** 本实例的消费者组名，供容器装配（建组 + 订阅）与 ACK 复用。 */
    public String groupName() {
        return groupName;
    }

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        Long version = parseVersion(message);
        if (version == null) {
            log.warn("忽略无法解析的配置同步消息（缺失或非法的 version 字段）: id={}", message.getId());
            return;
        }
        try {
            service.syncFromBroadcast(version);
            acknowledge(message);
        } catch (Exception e) {
            // 不 ACK：消息留在消费者组 PEL 中；本实例处理失败属极端场景，
            // 由周期对账兜底收敛（消费不 ACK 的兜底语义，见类注释）
            log.error("配置同步消息处理失败（不 ACK，由周期对账兜底）: version={}, cause={}",
                    version, e.getMessage());
        }
    }

    private void acknowledge(MapRecord<String, String, String> message) {
        try {
            redisTemplate.opsForStream()
                    .acknowledge(SystemConfigService.CONFIG_SYNC_STREAM_KEY, groupName, message.getId());
        } catch (Exception e) {
            // ACK 失败只影响 PEL 记账，不影响配置正确性（处理已完成且幂等）
            log.warn("配置同步消息 ACK 失败（不影响正确性）: id={}, cause={}", message.getId(), e.getMessage());
        }
    }

    private Long parseVersion(MapRecord<String, String, String> message) {
        String raw = message.getValue().get("version");
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析实例标识作为消费者组名：主机名 + 服务端口。
     * 同主机不同端口（本地起多实例）、不同主机同端口（容器扩缩容，Pod 主机名唯一）均可区分；
     * 重启后组名稳定，PEL 不会随重启无限堆积（stream 条目被 MAXLEN 裁剪后 PEL 自然清空）。
     * 主机名解析失败时退化为随机后缀（极端环境兜底，代价是重启后遗留旧组，组列表需手工清理）。
     */
    private static String resolveGroupName(int serverPort) {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return GROUP_PREFIX + host + "-" + serverPort;
    }
}

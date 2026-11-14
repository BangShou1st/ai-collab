package com.shitulelv.aicollab.agent.infrastructure.mcp;

/**
 * Provider 类型由服务端根据已通过出网策略校验的 endpoint 推导，客户端不能自行声明。
 */
public enum McpProviderType {
    EXTERNAL_READ_ONLY,
    LOCAL_FIXTURE
}

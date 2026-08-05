package com.shitulelv.aicollab.agent.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class McpClientFactory {
    private final ModelSecretCipher secrets;
    private final ObjectMapper json;
    private final McpEndpointPolicy endpoints;

    public McpClientFactory(ModelSecretCipher secrets, ObjectMapper json, McpEndpointPolicy endpoints) {
        this.secrets = secrets; this.json = json; this.endpoints = endpoints;
    }

    public McpClientFacade create(McpConnection connection) {
        if (connection.transport() == McpTransport.STDIO)
            throw new BusinessException(ErrorCode.AGENT_MCP_ENDPOINT_FORBIDDEN, "STDIO MCP 默认禁用");
        String credential = connection.authType() == McpAuthType.NONE ? null
                : secrets.decrypt(connection.credentialCiphertext());
        return new HttpMcpClientFacade(URI.create(connection.endpoint()), credential,
                connection.timeoutMs(), connection.maxResultBytes(), json, endpoints);
    }
}

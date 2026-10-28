package com.shitulelv.aicollab.connector.infrastructure.feishu;

public record FeishuFile(String token, String name, String type, String url, Long modifiedTime) {
    public boolean isDocx() {
        return "docx".equals(type);
    }
}

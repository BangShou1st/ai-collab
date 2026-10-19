package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentProcessingServiceMessageTest {

    @Test
    void explainsThatSystemEmbeddingMustBeConfigured() {
        BusinessException exception = new BusinessException(
                ErrorCode.VALIDATION_ERROR,
                "系统未配置嵌入模型，请联系管理员在管理中心 AI Infrastructure 中配置");

        assertThat(DocumentProcessingService.safeMessage(exception))
                .isEqualTo("文档索引暂不可用：系统尚未配置嵌入模型，请联系管理员在管理中心配置后重试");
    }

    @Test
    void keepsOtherValidationMessagesGeneric() {
        BusinessException exception = new BusinessException(ErrorCode.VALIDATION_ERROR, "内部校验细节");

        assertThat(DocumentProcessingService.safeMessage(exception))
                .isEqualTo("文档处理失败，请重试");
    }
}

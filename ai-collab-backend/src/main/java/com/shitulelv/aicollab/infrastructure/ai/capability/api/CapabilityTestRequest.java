package com.shitulelv.aicollab.infrastructure.ai.capability.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

/** query/documents 都可省略；省略时执行冻结的两文档相关性 smoke test。 */
public record CapabilityTestRequest(
        @Size(max = 2000) String query,
        @Size(max = 50) List<@Valid CapabilityTestDocument> documents,
        @Min(1) @Max(50) Integer topK,
        @Size(max = 500) String reason) {
}

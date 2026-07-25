package com.shitulelv.aicollab.work.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCommentRequest(@NotBlank @Size(max = 2000) String content) {
}

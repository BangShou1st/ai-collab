package com.shitulelv.aicollab.document.application.view;

import java.time.OffsetDateTime;

public record DownloadUrlView(String url, OffsetDateTime expiresAt) {
}

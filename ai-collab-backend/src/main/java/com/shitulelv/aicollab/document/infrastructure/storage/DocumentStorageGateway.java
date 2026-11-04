package com.shitulelv.aicollab.document.infrastructure.storage;

import com.shitulelv.aicollab.document.application.view.DownloadUrlView;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

public interface DocumentStorageGateway {
    void put(String objectKey, InputStream input, long size, String contentType);
    InputStream open(String objectKey);
    DownloadUrlView presign(String objectKey, String filename, Duration duration);
    void delete(String objectKey);
    List<String> list(String prefix, int maxKeys);
}

package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.document.infrastructure.repository.DocumentVisibilityRepository;
import com.shitulelv.aicollab.infrastructure.storage.MinioProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StorageAuthorizationService {
    private final DocumentVisibilityRepository visibility;
    private final MinioProperties minio;
    private final String proxyAddress;

    public StorageAuthorizationService(DocumentVisibilityRepository visibility, MinioProperties minio,
            @Value("${storage.minio.trusted-proxy-address:172.30.173.2}") String proxyAddress) {
        this.visibility = visibility;
        this.minio = minio;
        this.proxyAddress = proxyAddress;
    }

    public boolean allowed(String remoteAddress, String path) {
        String prefix = "/storage/" + minio.bucket() + "/";
        if (!proxyAddress.equals(remoteAddress) || path == null || path.length() > 2048
                || !path.startsWith(prefix) || path.length() == prefix.length()
                || path.indexOf('?') >= 0 || path.indexOf('#') >= 0
                || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) return false;
        return visibility.readableObject(path.substring(prefix.length()));
    }
}

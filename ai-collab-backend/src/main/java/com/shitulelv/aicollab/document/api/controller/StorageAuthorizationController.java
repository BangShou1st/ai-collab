package com.shitulelv.aicollab.document.api.controller;

import com.shitulelv.aicollab.document.application.service.StorageAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Internal Nginx subrequest: query strings and signatures are never forwarded here. */
@RestController
public class StorageAuthorizationController {
    private final StorageAuthorizationService service;

    public StorageAuthorizationController(StorageAuthorizationService service) {
        this.service = service;
    }

    @GetMapping("/internal/storage-authorize")
    public ResponseEntity<Void> authorize(HttpServletRequest request,
            @RequestHeader(value = "X-Storage-Path", required = false) String path) {
        return service.allowed(request.getRemoteAddr(), path)
                ? ResponseEntity.noContent().build() : ResponseEntity.status(403).build();
    }
}

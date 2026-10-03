package com.shitulelv.aicollab.document.api.controller;
import com.shitulelv.aicollab.document.application.service.DocumentContentService;
import com.shitulelv.aicollab.common.api.ApiResponse;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/documents/{documentId}")
public class DocumentContentController {
    private final DocumentContentService content;
    public DocumentContentController(DocumentContentService content) {this.content=content;}
    @GetMapping("/reading") public ApiResponse<ObjectNode> status(@PathVariable UUID projectId,@PathVariable UUID documentId,@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(content.status(projectId,documentId,UUID.fromString(jwt.getSubject())));
    }
    @GetMapping("/outline") public ApiResponse<ObjectNode> outline(@PathVariable UUID projectId,@PathVariable UUID documentId,@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(content.outline(projectId,documentId,UUID.fromString(jwt.getSubject())));
    }
    @GetMapping("/body") public ApiResponse<ObjectNode> read(@PathVariable UUID projectId,@PathVariable UUID documentId,@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) UUID snapshotId,@RequestParam(defaultValue="0") Integer fromChunk,@RequestParam(defaultValue="0") int fromOffset,
            @RequestParam(defaultValue="3000") int maxChars,@RequestParam(required=false) String heading,@RequestParam(required=false) UUID chunkId) {
        return ApiResponse.success(content.read(projectId,documentId,UUID.fromString(jwt.getSubject()),snapshotId,fromChunk,fromOffset,maxChars,heading,chunkId));
    }
}

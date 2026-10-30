package com.shitulelv.aicollab.document.application.view;

import java.io.InputStream;

/**
 * 文档下载流包装，包含文件输入流与元数据响应头所需信息。
 */
public record DocumentDownloadResource(
        InputStream stream,
        String filename,
        String mimeType,
        long sizeBytes
) {}

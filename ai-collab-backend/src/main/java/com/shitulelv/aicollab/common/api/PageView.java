package com.shitulelv.aicollab.common.api;

import java.util.List;

/**
 * 有界分页列表的统一契约。
 *
 * <p>{@code hasNext} 由服务端按 total 计算，客户端不得用当前页条数自行推断。</p>
 */
public record PageView<T>(
        List<T> items,
        long total,
        int page,
        int size,
        boolean hasNext) {
}

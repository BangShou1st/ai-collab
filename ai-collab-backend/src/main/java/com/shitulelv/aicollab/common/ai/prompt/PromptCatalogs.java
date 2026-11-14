package com.shitulelv.aicollab.common.ai.prompt;

/**
 * 无容器场景的 PromptCatalog 工厂。
 *
 * <p>设计动机：domain 层的 {@code AgentScenarioRegistry} 等组件需要在 Spring 之外
 * （单测、eval 工具、脚本）拿到与 production 同一份内置 PromptPack，而不想为此改动
 * 几十处构造签名。本类不依赖 Spring 容器，仅从默认 classpath 资源构建
 * {@link BundledPromptCatalog}，<b>不做任何网络访问</b>。</p>
 *
 * <p>与 {@link BundledPromptCatalog} 一致：加载失败不抛异常，之后每次
 * {@code require} 抛 {@code BusinessException(AI_PROMPT_UNAVAILABLE)}。</p>
 */
public final class PromptCatalogs {

    private PromptCatalogs() {
    }

    /**
     * 从默认 classpath 资源（{@code promptpack/}）构造 bundled 目录。
     *
     * <p>每次调用返回新实例（14 个小文件，加载成本低，不做静态缓存以免测试/热重载
     * 之间共享状态）。</p>
     */
    public static PromptCatalog bundled() {
        return new BundledPromptCatalog();
    }
}

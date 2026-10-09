package com.shitulelv.aicollab.common.testing;

/**
 * 测试用 MinIO 服务端镜像的唯一来源。
 *
 * <p>MinIO 已停止发布可匿名拉取的官方镜像：Docker Hub 的 {@code minio/minio} 仓库移除，
 * MinIO 自家 registry {@code quay.io/minio/minio} 现在对匿名请求返回 401。原来的
 * {@code quay.io/minio/minio:latest} 会导致依赖它的用例在 CI 上拉取镜像即失败
 * （Testcontainers 报 {@code ContainerFetch ... unauthorized}），而不是测试断言失败。</p>
 *
 * <p>这里改用 Chainguard 构建的同一服务端，按 digest 固定以保证可复现。它同样接受
 * {@code server /data} 命令与 {@code MINIO_ROOT_USER}/{@code MINIO_ROOT_PASSWORD}，
 * {@code /minio/health/ready} 健康检查语义不变。注意 Chainguard 免费层只发布
 * {@code latest} 标签，digest 会被回收，后续失效属预期，升级时同步更新 digest。</p>
 */
public final class MinioTestImage {
    /** 按 digest 固定的 MinIO 服务端镜像；替换时须实际验证健康检查与根凭据行为。 */
    public static final String IMAGE =
            "cgr.dev/chainguard/minio:latest@sha256:f74600a1a46330cdbda1ef760d17a96bd6e0f4a6f0a2c49792ca3ee7e4c6fa18";

    private MinioTestImage() {}
}

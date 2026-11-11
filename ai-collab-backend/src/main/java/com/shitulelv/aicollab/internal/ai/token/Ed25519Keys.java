package com.shitulelv.aicollab.internal.ai.token;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Ed25519 密钥工具：PKCS#8 私钥、SPKI 公钥解析，以及从私钥派生公钥。
 *
 * <p><b>为什么手写公钥派生</b>：JDK 只提供 {@code EdECPrivateKey#getBytes()}（32 字节 seed），
 * 没有"由私钥推导公钥"的公开 API，而契约要求 {@code public-keys} 为空时用当前私钥派生当前
 * {@code kid} 的公钥（Java 侧要验证 worker 回传的同一令牌）。这里按 RFC 8032 做一次
 * 标量乘基点，只在启动时执行一次，不引入任何第三方依赖。</p>
 *
 * <p><b>不泄露密钥材料</b>：所有异常消息只描述格式问题，绝不包含 base64 或字节内容。</p>
 */
public final class Ed25519Keys {

    private static final String KEY_ALGORITHM = "Ed25519";

    /** Ed25519 公钥 SPKI DER 的固定前缀（RFC 8410）。 */
    private static final byte[] SPKI_PREFIX = {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger BASE_X = new BigInteger(
            "15112221349535400772501151409588531511454012693041857206046113283949847762202");
    private static final BigInteger BASE_Y = new BigInteger(
            "46316835694926478169428394003475163141307993866256225615783033603165251855960");
    private static final Point BASE = new Point(BASE_X, BASE_Y);
    private static final Point IDENTITY = new Point(BigInteger.ZERO, BigInteger.ONE);

    private Ed25519Keys() {
    }

    /**
     * 解析 PKCS#8 DER 的 base64 私钥（允许换行/空白，便于多行注入）。
     *
     * @throws IllegalArgumentException 格式非法；消息不含密钥内容
     */
    public static PrivateKey parsePrivateKey(String base64) {
        byte[] der = decodeBase64(base64, "私钥");
        try {
            PrivateKey key = KeyFactory.getInstance(KEY_ALGORITHM)
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!(key instanceof EdECPrivateKey)) {
                throw new IllegalArgumentException("不是 Ed25519 私钥");
            }
            return key;
        } catch (GeneralSecurityException invalid) {
            throw new IllegalArgumentException("不是合法的 PKCS#8 Ed25519 私钥");
        }
    }

    /**
     * 解析 SPKI DER 的 base64 公钥。
     *
     * @throws IllegalArgumentException 格式非法；消息不含密钥内容
     */
    public static PublicKey parsePublicKey(String base64) {
        byte[] der = decodeBase64(base64, "公钥");
        try {
            PublicKey key = KeyFactory.getInstance(KEY_ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(der));
            if (!(key instanceof EdECPublicKey)) {
                throw new IllegalArgumentException("不是 Ed25519 公钥");
            }
            return key;
        } catch (GeneralSecurityException invalid) {
            throw new IllegalArgumentException("不是合法的 SPKI DER Ed25519 公钥");
        }
    }

    /**
     * 由 Ed25519 私钥派生公钥（RFC 8032 §5.1.5：clamp(SHA-512(seed)[0:32]) × B）。
     *
     * @throws IllegalArgumentException 私钥不是 Ed25519 或缺少 seed
     */
    public static PublicKey derivePublicKey(PrivateKey privateKey) {
        if (!(privateKey instanceof EdECPrivateKey edPrivate)) {
            throw new IllegalArgumentException("不是 Ed25519 私钥");
        }
        byte[] seed = edPrivate.getBytes()
                .orElseThrow(() -> new IllegalArgumentException("Ed25519 私钥缺少 seed 字节"));
        return publicKeyFromSeed(seed);
    }

    /** 由 32 字节 seed 计算 SPKI 公钥；包可见之外也用于 RFC 8032 向量自检。 */
    public static PublicKey publicKeyFromSeed(byte[] seed) {
        if (seed == null || seed.length != 32) {
            throw new IllegalArgumentException("Ed25519 seed 必须是 32 字节");
        }
        byte[] digest = sha512(seed);
        byte[] scalarBytes = Arrays.copyOfRange(digest, 0, 32);
        scalarBytes[0] &= (byte) 248;
        scalarBytes[31] &= 127;
        scalarBytes[31] |= 64;
        BigInteger scalar = new BigInteger(1, reverse(scalarBytes));
        Point publicPoint = scalarMultiply(scalar, BASE);
        byte[] encoded = encodePoint(publicPoint);
        byte[] spki = new byte[SPKI_PREFIX.length + encoded.length];
        System.arraycopy(SPKI_PREFIX, 0, spki, 0, SPKI_PREFIX.length);
        System.arraycopy(encoded, 0, spki, SPKI_PREFIX.length, encoded.length);
        try {
            return KeyFactory.getInstance(KEY_ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(spki));
        } catch (GeneralSecurityException unreachable) {
            throw new IllegalStateException("Ed25519 公钥构造失败", unreachable);
        }
    }

    /** 标准 base64 编码（SPKI/PKCS#8 DER），供测试与 fixture 使用。 */
    public static String encodeBase64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    static byte[] decodeBase64(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + "不能为空");
        }
        String normalized = value.replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(normalized);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(what + "不是合法的 base64");
        }
    }

    private static Point scalarMultiply(BigInteger scalar, Point point) {
        Point result = IDENTITY;
        Point addend = point;
        BigInteger remaining = scalar;
        while (remaining.signum() > 0) {
            if (remaining.testBit(0)) {
                result = add(result, addend);
            }
            addend = add(addend, addend);
            remaining = remaining.shiftRight(1);
        }
        return result;
    }

    /** 扭曲 Edwards 曲线（a=-1）的仿射加法；分母在 Ed25519 上恒非零。 */
    private static Point add(Point left, Point right) {
        BigInteger x1y2 = left.x().multiply(right.y()).mod(P);
        BigInteger x2y1 = right.x().multiply(left.y()).mod(P);
        BigInteger y1y2 = left.y().multiply(right.y()).mod(P);
        BigInteger x1x2 = left.x().multiply(right.x()).mod(P);
        BigInteger dProduct = D.multiply(x1x2).mod(P).multiply(y1y2).mod(P);
        BigInteger xNumerator = x1y2.add(x2y1).mod(P);
        BigInteger yNumerator = y1y2.add(x1x2).mod(P);
        BigInteger xDenominator = BigInteger.ONE.add(dProduct).mod(P);
        BigInteger yDenominator = BigInteger.ONE.subtract(dProduct).mod(P);
        return new Point(
                xNumerator.multiply(xDenominator.modInverse(P)).mod(P),
                yNumerator.multiply(yDenominator.modInverse(P)).mod(P));
    }

    private static byte[] encodePoint(Point point) {
        byte[] encoded = new byte[32];
        BigInteger y = point.y();
        for (int index = 0; index < 32; index++) {
            encoded[index] = y.shiftRight(8 * index).byteValue();
        }
        if (point.x().testBit(0)) {
            encoded[31] |= (byte) 0x80;
        }
        return encoded;
    }

    private static byte[] sha512(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-512").digest(input);
        } catch (GeneralSecurityException unreachable) {
            throw new IllegalStateException("SHA-512 不可用", unreachable);
        }
    }

    private static byte[] reverse(byte[] value) {
        byte[] reversed = new byte[value.length];
        for (int index = 0; index < value.length; index++) {
            reversed[index] = value[value.length - 1 - index];
        }
        return reversed;
    }

    private static String hex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte item : value) {
            builder.append(String.format("%02x", item));
        }
        return builder.toString();
    }

    /** 仅供测试断言使用：把点编码成小写十六进制。 */
    static String hexPublicKeyFromSeed(byte[] seed) {
        PublicKey key = publicKeyFromSeed(seed);
        try {
            byte[] spki = key.getEncoded();
            byte[] raw = Arrays.copyOfRange(spki, SPKI_PREFIX.length, spki.length);
            return hex(raw);
        } catch (RuntimeException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Point(BigInteger x, BigInteger y) {
    }
}

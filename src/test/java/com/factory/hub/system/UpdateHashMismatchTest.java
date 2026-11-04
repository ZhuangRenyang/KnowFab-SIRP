package com.factory.hub.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DH-25：下载前比对 {@code version.json} 声明的 sha256。
 * <p>
 * <b>这一关补的是什么</b>：现有升级链路已经有两道校验——
 * 验签（挡伪造包）和 manifest 逐文件 sha256（挡包内文件被换）。
 * 但它们挡不住两种情况：
 * <ol>
 *   <li><b>传输截断</b>：下载到一半网络断了，zip 不完整。签名校验会失败，
 *       但用户看到的报错是「签名不对」，会以为包是坏的/被攻击了</li>
 *   <li><b>仓库附件被换成另一个合法包</b>：两个都是官方签过的包，
 *       验签都过，但装上去的不是刚才看到的那个版本</li>
 * </ol>
 * 比对 version.json 里的哈希能把这两种情况提前挡住，且报错说得清是哪种。
 */
class UpdateHashMismatchTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("哈希一致 → 通过（返回 null）")
    void sameHashPasses() {
        byte[] data = bytes("device-hub upgrade package v1.0.24");
        String sha = MigrationScript.sha256(data);
        assertNull(UpdateService.hashMismatch(data, sha), "一致就该放行");
    }

    @Test
    @DisplayName("哈希不一致 → 拒绝，且报错写明声明值与实际值")
    void differentHashRejected() {
        byte[] data = bytes("真实的包内容");
        String wrong = MigrationScript.sha256(bytes("另一个包的内容"));
        String msg = UpdateService.hashMismatch(data, wrong);
        assertNotNull(msg, "不一致必须拒绝");
        assertTrue(msg.contains("不一致"), "要说清哪里不对：" + msg);
        // 两个哈希都要露出来，否则运维没法判断是"下坏了"还是"附件被换了"
        assertTrue(msg.contains(wrong.substring(0, 12)), "要带声明值：" + msg);
        assertTrue(msg.contains(MigrationScript.sha256(data).substring(0, 12)),
                "要带实际值：" + msg);
    }

    @Test
    @DisplayName("大小写不敏感：哈希是十六进制，大写小写不能算两种")
    void caseInsensitive() {
        byte[] data = bytes("case test");
        String sha = MigrationScript.sha256(data);
        assertNull(UpdateService.hashMismatch(data, sha.toUpperCase()), "大写也该通过");
        assertNull(UpdateService.hashMismatch(data, "  " + sha + "  "), "前后空格要容忍");
    }

    @Test
    @DisplayName("没声明哈希 → 跳过（不阻塞）")
    void noExpectedHashSkips() {
        byte[] data = bytes("whatever");
        assertNull(UpdateService.hashMismatch(data, null), "没声明就该跳过");
        assertNull(UpdateService.hashMismatch(data, ""), "空串也该跳过");
        assertNull(UpdateService.hashMismatch(data, "   "), "空白也该跳过");
    }

    @Test
    @DisplayName("内容为空 → 拒绝（不是「跳过」）")
    void nullDataRejected() {
        String msg = UpdateService.hashMismatch(null, "abc123");
        assertNotNull(msg, "声明了哈希但内容是空的，必须拒绝");
        assertTrue(msg.contains("空"), msg);
    }

    @Test
    @DisplayName("空内容 vs 空文件的哈希：空文件哈希是固定的 e3b0c442…")
    void emptyFileHasKnownHash() {
        // 这条钉住的是「哈希是对字节算的」这件事：
        // 空文件的 sha256 是 e3b0c442...，不是空串。
        // 如果有人把 hashMismatch 写成"expected 为空就跳过"，这条不会红；
        // 但如果哪天有人把计算改成对 String 算而漏了编码，这条会红。
        String emptySha = MigrationScript.sha256(new byte[0]);
        assertTrue(emptySha.startsWith("e3b0c442"), "空文件的 sha256 应是 e3b0c442…：" + emptySha);
        assertNull(UpdateService.hashMismatch(new byte[0], emptySha), "空文件配空文件哈希该通过");
        assertNotNull(UpdateService.hashMismatch(bytes("x"), emptySha), "有内容配空文件哈希该拒绝");
    }
}

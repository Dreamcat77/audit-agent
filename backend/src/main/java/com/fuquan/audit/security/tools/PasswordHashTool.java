package com.fuquan.audit.security.tools;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 为本地首账号初始化离线生成 Spring Security 委托式密码哈希。
 *
 * <p>工具只从交互式控制台或受保护的标准输入读取密码，不接受命令行明文参数，避免密码进入进程列表或命令历史。</p>
 */
public final class PasswordHashTool {

    /**
     * 工具类不允许实例化。
     */
    private PasswordHashTool() {
    }

    /**
     * 读取并校验密码后输出可用于 {@code AUDIT_LOCAL_ACCOUNT_PASSWORD_HASH} 的委托式哈希。
     *
     * <p>默认要求交互式二次确认；仅在显式传入 {@code --stdin} 时读取标准输入。使用结束后会覆盖内存中的字符数组。</p>
     *
     * @param args 可选的 {@code --stdin} 模式参数
     * @throws IOException 标准输入读取失败时抛出
     */
    public static void main(String[] args) throws IOException {
        boolean stdinMode = args.length == 1 && "--stdin".equals(args[0]);
        if (args.length > 0 && !stdinMode) {
            throw new IllegalArgumentException("usage: PasswordHashTool [--stdin]");
        }
        char[] password = stdinMode ? readFromStdin() : readFromConsole();
        try {
            if (password.length == 0) {
                throw new IllegalArgumentException("password must not be empty");
            }
            PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
            String encoded = encoder.encode(CharBuffer.wrap(password));
            new PrintWriter(System.out, true, StandardCharsets.UTF_8).println(encoded);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * 从交互式控制台读取两次密码，并在内容一致后返回首份字符数组。
     *
     * @return 已完成二次确认的密码字符数组，由调用方负责使用后覆盖
     * @throws IllegalStateException 当前进程没有可用交互式控制台时抛出
     * @throws IllegalArgumentException 两次输入不一致或输入被取消时抛出
     */
    private static char[] readFromConsole() {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException(
                    "interactive console unavailable; use --stdin with a protected pipeline");
        }
        char[] first = console.readPassword("Password: ");
        char[] second = console.readPassword("Confirm password: ");
        if (first == null || second == null || !Arrays.equals(first, second)) {
            if (first != null) {
                Arrays.fill(first, '\0');
            }
            if (second != null) {
                Arrays.fill(second, '\0');
            }
            throw new IllegalArgumentException("password confirmation does not match");
        }
        Arrays.fill(second, '\0');
        return first;
    }

    /**
     * 从标准输入读取一行密码，供受保护的非交互式管道调用。
     *
     * @return 输入密码的可覆盖字符数组
     * @throws IOException 标准输入读取失败时抛出
     * @throws IllegalArgumentException 标准输入未提供内容时抛出
     */
    private static char[] readFromStdin() throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String value = reader.readLine();
        if (value == null) {
            throw new IllegalArgumentException("password input is missing");
        }
        return value.toCharArray();
    }
}

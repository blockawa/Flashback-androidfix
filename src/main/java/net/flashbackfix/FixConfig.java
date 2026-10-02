package net.flashbackfix;

import com.moulberry.flashback.Flashback;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本 mod 自有的设置：{@code config/flashback/flashback-androidfix.json}。
 *
 * <p>存放 imgui 界面缩放。滑块或重置按钮改动时立即调用 {@link #apply()}——写盘并把值交给
 * {@link #appliedScale()}，下一帧经 {@code ReplayUIMixin} 实时生效，无需手动确认。
 */
public final class FixConfig {

    public float imguiScale = 1.0f;

    private float appliedScale = 1.0f;

    private static FixConfig instance;

    public static FixConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static Path file() {
        return Flashback.getConfigDirectory().resolve("flashback-androidfix.json");
    }

    private static FixConfig load() {
        FixConfig config = new FixConfig();
        Path path = file();
        if (!Files.exists(path)) return config;
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            config.imguiScale = readFloat(json, "imguiScale", config.imguiScale);
        } catch (IOException ignored) {
        }
        config.appliedScale = config.imguiScale;
        return config;
    }

    private static float readFloat(String json, String key, float fallback) {
        int i = json.indexOf('"' + key + '"');
        if (i < 0) return fallback;
        int colon = json.indexOf(':', i);
        if (colon < 0) return fallback;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        while (end < json.length()
                && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-' || json.charAt(end) == '.')) end++;
        if (end == start) return fallback;
        try {
            return Float.parseFloat(json.substring(start, end));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public float appliedScale() {
        return appliedScale;
    }

    public void apply() {
        save();
        appliedScale = imguiScale;
    }

    public void save() {
        String json = "{\n"
                + "  \"imguiScale\": " + imguiScale + "\n"
                + "}\n";
        try {
            Files.writeString(file(), json, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}

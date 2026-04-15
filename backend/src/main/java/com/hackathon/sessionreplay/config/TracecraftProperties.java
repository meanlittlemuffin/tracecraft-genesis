package com.hackathon.sessionreplay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "tracecraft")
public class TracecraftProperties {

    private Logs logs = new Logs();
    private Ai ai = new Ai();
    private CodeContext codeContext = new CodeContext();

    public Logs getLogs() {
        return logs;
    }

    public void setLogs(Logs logs) {
        this.logs = logs;
    }

    public Ai getAi() {
        return ai;
    }

    public void setAi(Ai ai) {
        this.ai = ai;
    }

    public CodeContext getCodeContext() {
        return codeContext;
    }

    public void setCodeContext(CodeContext codeContext) {
        this.codeContext = codeContext;
    }

    public static class Logs {
        private boolean enabled = true;
        private List<String> demoHosts = new ArrayList<>(List.of("localhost", "127.0.0.1"));
        private String filePath = "${user.dir}/../demo-app/logs/demo-app.log";
        private int lookbackSeconds = 30;
        private int maxBytesRead = 1_048_576;
        private int maxLinesForAi = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getDemoHosts() {
            return demoHosts;
        }

        public void setDemoHosts(List<String> demoHosts) {
            this.demoHosts = demoHosts;
        }

        public String getFilePath() {
            return filePath;
        }

        public void setFilePath(String filePath) {
            this.filePath = filePath;
        }

        public int getLookbackSeconds() {
            return lookbackSeconds;
        }

        public void setLookbackSeconds(int lookbackSeconds) {
            this.lookbackSeconds = lookbackSeconds;
        }

        public int getMaxBytesRead() {
            return maxBytesRead;
        }

        public void setMaxBytesRead(int maxBytesRead) {
            this.maxBytesRead = maxBytesRead;
        }

        public int getMaxLinesForAi() {
            return maxLinesForAi;
        }

        public void setMaxLinesForAi(int maxLinesForAi) {
            this.maxLinesForAi = maxLinesForAi;
        }
    }

    public static class Ai {
        private int maxPacketChars = 40_000;
        private int targetPacketChars = 30_000;
        private int cooldownSeconds = 30;
        private boolean cacheEnabled = true;
        private int cacheTtlSeconds = 900;

        public int getMaxPacketChars() {
            return maxPacketChars;
        }

        public void setMaxPacketChars(int maxPacketChars) {
            this.maxPacketChars = maxPacketChars;
        }

        public int getTargetPacketChars() {
            return targetPacketChars;
        }

        public void setTargetPacketChars(int targetPacketChars) {
            this.targetPacketChars = targetPacketChars;
        }

        public int getCooldownSeconds() {
            return cooldownSeconds;
        }

        public void setCooldownSeconds(int cooldownSeconds) {
            this.cooldownSeconds = cooldownSeconds;
        }

        public boolean isCacheEnabled() {
            return cacheEnabled;
        }

        public void setCacheEnabled(boolean cacheEnabled) {
            this.cacheEnabled = cacheEnabled;
        }

        public int getCacheTtlSeconds() {
            return cacheTtlSeconds;
        }

        public void setCacheTtlSeconds(int cacheTtlSeconds) {
            this.cacheTtlSeconds = cacheTtlSeconds;
        }
    }

    public static class CodeContext {
        private boolean enabled = true;
        private String sourceRoot = "${user.dir}/../demo-app/src/main/java";
        private int maxFiles = 3;
        private int maxSnippetLines = 50;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSourceRoot() {
            return sourceRoot;
        }

        public void setSourceRoot(String sourceRoot) {
            this.sourceRoot = sourceRoot;
        }

        public int getMaxFiles() {
            return maxFiles;
        }

        public void setMaxFiles(int maxFiles) {
            this.maxFiles = maxFiles;
        }

        public int getMaxSnippetLines() {
            return maxSnippetLines;
        }

        public void setMaxSnippetLines(int maxSnippetLines) {
            this.maxSnippetLines = maxSnippetLines;
        }
    }
}

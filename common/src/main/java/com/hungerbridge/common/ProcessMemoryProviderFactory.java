package com.hungerbridge.common;

public final class ProcessMemoryProviderFactory {
    private ProcessMemoryProviderFactory() {}

    public static ProcessMemoryProvider create() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return new WindowsProcessMemoryProvider();
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return new MacProcessMemoryProvider();
        }
        if (os.contains("linux")) {
            return new LinuxProcessMemoryProvider();
        }
        return new UnsupportedProcessMemoryProvider();
    }

    private static final class UnsupportedProcessMemoryProvider implements ProcessMemoryProvider {
        @Override public long getProcessUsedBytes() { return 0L; }
        @Override public long getProcessVirtualBytes() { return 0L; }
    }
}

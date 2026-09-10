package com.hungerbridge.common;

public final class WindowsProcessMemoryProvider implements ProcessMemoryProvider {
    @Override
    public long getProcessUsedBytes() {
        return 0L;
    }

    @Override
    public long getProcessVirtualBytes() {
        return 0L;
    }
}

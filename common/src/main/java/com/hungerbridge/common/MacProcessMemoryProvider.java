package com.hungerbridge.common;

public final class MacProcessMemoryProvider implements ProcessMemoryProvider {
    @Override
    public long getProcessUsedBytes() {
        return 0L;
    }

    @Override
    public long getProcessVirtualBytes() {
        return 0L;
    }
}

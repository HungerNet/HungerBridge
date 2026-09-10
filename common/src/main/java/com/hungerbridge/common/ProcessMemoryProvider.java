package com.hungerbridge.common;

public interface ProcessMemoryProvider {
    long getProcessUsedBytes();
    long getProcessVirtualBytes();
}

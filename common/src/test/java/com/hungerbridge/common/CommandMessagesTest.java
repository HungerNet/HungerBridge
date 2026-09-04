package com.hungerbridge.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public final class CommandMessagesTest {

    @Test
    public void formatKeyValuesProducesLines() {
        var m = Map.of("a", 1, "b", "two", "nested", Map.of("x", "y"));
        List<String> lines = CommandMessages.formatKeyValues(m);
        assertFalse(lines.isEmpty());
        assertTrue(lines.stream().anyMatch(s -> s.startsWith("a:" ) || s.startsWith("a: ") || s.contains("a: 1")));
    }

    @Test
    public void formatTableAlignsColumns() {
        List<String[]> rows = List.of(new String[]{"one", "two"}, new String[]{"three", "four"});
        String[] headers = new String[]{"H1", "H2"};
        List<String> out = CommandMessages.formatTable(rows, headers);
        assertFalse(out.isEmpty());
        // first line should contain H1 and H2
        assertTrue(out.get(0).contains("H1") && out.get(0).contains("H2"));
    }

    @Test
    public void formatListNumberedAndBulleted() {
        List<String> items = List.of("a", "b", "c");
        List<String> numbered = CommandMessages.formatList(items, true);
        List<String> bulleted = CommandMessages.formatList(items, false);
        assertEquals(3, numbered.size());
        assertEquals(3, bulleted.size());
        assertTrue(numbered.get(0).matches("\\s*1\\..*"));
        assertTrue(bulleted.get(0).startsWith("- "));
    }

    @Test
    public void formatStatusIncludesName() {
        String s = CommandMessages.formatStatus("test", 123);
        assertTrue(s.contains("test"));
        assertTrue(s.contains("123"));
    }

    @Test
    public void createdTokenUsesColor() {
        String s = CommandMessages.createdToken("id", "sec");
        assertTrue(s.contains("Created token"));
        assertTrue(s.contains("\u001B["));
    }
}

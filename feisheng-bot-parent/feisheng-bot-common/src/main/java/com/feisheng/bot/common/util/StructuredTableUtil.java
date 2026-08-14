package com.feisheng.bot.common.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stable, lossless text representation for OCR tables.
 *
 * <p>Plain TSV is convenient while an image is being recognised, but generic
 * text normalisation can collapse its column boundaries later in the import
 * pipeline.  This format keeps every value attached to its OCR'd header and is
 * still readable by a language model when it is included as evidence.</p>
 */
public final class StructuredTableUtil {
    public static final String TABLE_START = "[结构化表格]";
    public static final String TABLE_END = "[/结构化表格]";
    public static final String HEADER_PREFIX = "表头：";
    public static final String ROW_PREFIX = "表格行：";
    private static final char FIELD_SEPARATOR = '；';
    private static final char KEY_VALUE_SEPARATOR = '=';
    private static final char ESCAPE = '\\';

    private StructuredTableUtil() {}

    /**
     * Serialises a rectangular OCR matrix. The first row is the header and is
     * repeated as a key on every data row so column relationships survive any
     * whitespace normalisation performed later.
     */
    public static String serialize(List<List<String>> matrix) {
        if (matrix == null || matrix.size() < 2) {
            throw new IllegalArgumentException("表格必须包含表头和至少一行数据");
        }
        List<String> headers = cleanRow(matrix.get(0));
        if (headers.isEmpty() || headers.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("表格表头为空，无法可靠识别");
        }
        Set<String> uniqueHeaders = new LinkedHashSet<>(headers);
        if (uniqueHeaders.size() != headers.size()) {
            throw new IllegalArgumentException("表格表头重复，无法可靠识别");
        }
        if (headers.stream().anyMatch(StructuredTableUtil::containsUnescapedDelimiter)) {
            throw new IllegalArgumentException("表格表头包含无法保存的分隔符");
        }

        StringBuilder result = new StringBuilder(TABLE_START).append('\n')
            .append(HEADER_PREFIX).append(joinEscaped(headers)).append('\n');
        int dataRows = 0;
        for (int rowIndex = 1; rowIndex < matrix.size(); rowIndex++) {
            List<String> row = cleanRow(matrix.get(rowIndex));
            if (row.size() != headers.size() || row.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException(
                    "表格第 " + (rowIndex + 1) + " 行列数或内容异常，无法可靠识别");
            }
            result.append(ROW_PREFIX);
            for (int column = 0; column < headers.size(); column++) {
                if (column > 0) result.append(FIELD_SEPARATOR);
                result.append(escape(headers.get(column))).append(KEY_VALUE_SEPARATOR)
                    .append(escape(row.get(column)));
            }
            result.append('\n');
            dataRows++;
        }
        if (dataRows == 0) throw new IllegalArgumentException("表格没有可用数据行");
        return result.append(TABLE_END).toString();
    }

    public static boolean containsTable(String text) {
        return text != null && text.contains(TABLE_START) && text.contains(TABLE_END);
    }

    /** Parses only the rows inside the explicit table markers. */
    public static Table parse(String text) {
        if (!containsTable(text)) return Table.empty();
        int start = text.indexOf(TABLE_START) + TABLE_START.length();
        int end = text.indexOf(TABLE_END, start);
        if (end < 0) return Table.empty();

        List<String> headers = new ArrayList<>();
        List<Map<String, String>> rows = new ArrayList<>();
        for (String rawLine : text.substring(start, end).split("\\R")) {
            String line = rawLine.trim();
            if (line.startsWith(HEADER_PREFIX)) {
                headers = parseHeader(line.substring(HEADER_PREFIX.length()));
            } else if (line.startsWith(ROW_PREFIX)) {
                Map<String, String> row = parseFields(line.substring(ROW_PREFIX.length()));
                if (!row.isEmpty()) rows.add(row);
            }
        }
        return new Table(headers, rows);
    }

    private static List<String> cleanRow(List<String> row) {
        if (row == null) return List.of();
        List<String> values = new ArrayList<>(row.size());
        for (String value : row) values.add(value == null ? "" : value.trim());
        return values;
    }

    private static boolean containsUnescapedDelimiter(String value) {
        return value.indexOf(FIELD_SEPARATOR) >= 0 || value.indexOf(KEY_VALUE_SEPARATOR) >= 0;
    }

    private static String joinEscaped(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) result.append(FIELD_SEPARATOR);
            result.append(escape(values.get(index)));
        }
        return result.toString();
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace(String.valueOf(ESCAPE), "\\\\")
            .replace(String.valueOf(FIELD_SEPARATOR), "\\；")
            .replace(String.valueOf(KEY_VALUE_SEPARATOR), "\\=");
    }

    private static List<String> parseHeader(String value) {
        List<String> result = new ArrayList<>();
        for (String field : splitEscaped(value, FIELD_SEPARATOR)) result.add(unescape(field).trim());
        return result;
    }

    private static Map<String, String> parseFields(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String field : splitEscaped(value, FIELD_SEPARATOR)) {
            int separator = indexOfUnescaped(field, KEY_VALUE_SEPARATOR);
            if (separator <= 0) continue;
            String key = unescape(field.substring(0, separator)).trim();
            String cell = unescape(field.substring(separator + 1)).trim();
            if (!key.isBlank()) result.put(key, cell);
        }
        return result;
    }

    private static List<String> splitEscaped(String value, char separator) {
        if (value == null || value.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (escaped) {
                current.append(ESCAPE).append(character);
                escaped = false;
            } else if (character == ESCAPE) {
                escaped = true;
            } else if (character == separator) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (escaped) current.append(ESCAPE);
        result.add(current.toString());
        return result;
    }

    private static int indexOfUnescaped(String value, char target) {
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (character == ESCAPE) {
                escaped = true;
            } else if (character == target) {
                return index;
            }
        }
        return -1;
    }

    private static String unescape(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (escaped) {
                result.append(character);
                escaped = false;
            } else if (character == ESCAPE) {
                escaped = true;
            } else {
                result.append(character);
            }
        }
        if (escaped) result.append(ESCAPE);
        return result.toString();
    }

    public record Table(List<String> headers, List<Map<String, String>> rows) {
        public Table {
            headers = headers == null ? List.of() : List.copyOf(headers);
            List<Map<String, String>> copy = new ArrayList<>();
            if (rows != null) {
                for (Map<String, String> row : rows) {
                    copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
                }
            }
            rows = Collections.unmodifiableList(copy);
        }

        public static Table empty() {
            return new Table(List.of(), List.of());
        }
    }
}

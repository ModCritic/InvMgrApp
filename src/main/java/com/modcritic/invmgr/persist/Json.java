package com.modcritic.invmgr.persist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes JSON text, matching JavaScript's behaviour closely enough to be
 * byte-compatible with the original HTML app's save files.
 *
 * <p><b>Why this is hand-written instead of using a library.</b> Three reasons, in order
 * of weight:
 *
 * <ol>
 *   <li><b>The shipped app has exactly one outside library</b> (OpenJFX), which keeps the
 *       licence position simple and the Android build small. A JSON library would be the
 *       second, for a file format that is nine keys deep.
 *   <li><b>The Android build compiles to a native image</b>, which strips code it cannot
 *       see being called. The popular JSON libraries work by reflection — inspecting
 *       classes at runtime — which is exactly what a native image breaks, and two of the
 *       six failures in the OD-1 spike were reflection problems that presented as a black
 *       screen with no error. Plain code has none of that risk.
 *   <li><b>We need <em>JavaScript's</em> conversion rules, not Java's</b>, to stay
 *       compatible: {@code Number(null)} is 0, {@code Number("")} is 0, and the number
 *       {@code 12} must be written as {@code 12}, not {@code 12.0}. A general-purpose
 *       library would need fighting on all three.
 * </ol>
 *
 * <p><b>What a parsed document looks like.</b> Deliberately loose types, because the save
 * file is untrusted input and the loader's whole job is coping with values of the wrong
 * type. A JSON object arrives as a {@code Map<String, Object>}, an array as a
 * {@code List<Object>}, and leaves as {@code String}, {@link Double}, {@link Boolean}, or
 * {@code null}. Nothing is coerced during parsing — that happens later, in
 * {@link SaveFormat}, where the original app's rules are applied.
 */
public final class Json {

    private Json() {
    }

    /** Thrown when text isn't valid JSON. Callers report it; they never let it escape. */
    public static class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------- reading

    /**
     * Parses JSON text into nested {@code Map}s, {@code List}s and leaves.
     *
     * @throws JsonException if the text is not valid JSON, including trailing junk
     */
    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonException("unexpected trailing content at position " + parser.pos);
        }
        return value;
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        boolean atEnd() {
            return pos >= src.length();
        }

        void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            if (atEnd()) {
                throw new JsonException("unexpected end of input");
            }
            char c = src.charAt(pos);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // consume '{'
            skipWhitespace();
            if (!atEnd() && src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || src.charAt(pos) != '"') {
                    throw new JsonException("expected a key in double quotes at position " + pos);
                }
                String key = readString();
                skipWhitespace();
                if (atEnd() || src.charAt(pos) != ':') {
                    throw new JsonException("expected ':' after key \"" + key + "\"");
                }
                pos++;
                skipWhitespace();
                // A repeated key overwrites the earlier one, which is what JavaScript
                // does, while keeping its original position in the key order.
                map.put(key, readValue());
                skipWhitespace();
                if (atEnd()) {
                    throw new JsonException("unterminated object");
                }
                char c = src.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return map;
                } else {
                    throw new JsonException("expected ',' or '}' at position " + pos);
                }
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // consume '['
            skipWhitespace();
            if (!atEnd() && src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(readValue());
                skipWhitespace();
                if (atEnd()) {
                    throw new JsonException("unterminated array");
                }
                char c = src.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw new JsonException("expected ',' or ']' at position " + pos);
                }
            }
        }

        private String readString() {
            pos++; // consume opening quote
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonException("unterminated string");
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    if (c < 0x20) {
                        throw new JsonException("unescaped control character in string at position "
                                + (pos - 1));
                    }
                    out.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new JsonException("unterminated escape sequence");
                }
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"':  out.append('"');  break;
                    case '\\': out.append('\\'); break;
                    case '/':  out.append('/');  break;
                    case 'b':  out.append('\b'); break;
                    case 'f':  out.append('\f'); break;
                    case 'n':  out.append('\n'); break;
                    case 'r':  out.append('\r'); break;
                    case 't':  out.append('\t'); break;
                    case 'u':
                        if (pos + 4 > src.length()) {
                            throw new JsonException("truncated \\u escape");
                        }
                        String hex = src.substring(pos, pos + 4);
                        try {
                            out.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw new JsonException("invalid \\u escape: \\u" + hex);
                        }
                        pos += 4;
                        break;
                    default:
                        throw new JsonException("invalid escape character: \\" + esc);
                }
            }
        }

        private Double readNumber() {
            int start = pos;
            if (!atEnd() && src.charAt(pos) == '-') {
                pos++;
            }
            while (!atEnd() && isNumberChar(src.charAt(pos))) {
                pos++;
            }
            String literal = src.substring(start, pos);
            if (literal.isEmpty()) {
                throw new JsonException("unexpected character '" + src.charAt(start)
                        + "' at position " + start);
            }
            try {
                // Double is the right target: JavaScript has no separate integer type, so
                // this is exactly the precision the original app had, including its loss
                // of precision above 2^53.
                return Double.valueOf(literal);
            } catch (NumberFormatException e) {
                throw new JsonException("invalid number: " + literal);
            }
        }

        private static boolean isNumberChar(char c) {
            return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                    || c == '+' || c == '-';
        }

        private void expect(String word) {
            if (!src.startsWith(word, pos)) {
                throw new JsonException("invalid literal at position " + pos + ", expected "
                        + word);
            }
            pos += word.length();
        }
    }

    // ---------------------------------------------------------------- writing

    /**
     * Writes a number the way JavaScript's {@code JSON.stringify} would: {@code 12}
     * rather than Java's {@code 12.0}.
     *
     * <p>This is not cosmetic. The compatibility contract asks for files the HTML app can
     * open and, ideally, files that are byte-identical to what it writes. Java's default
     * formatting differs on every whole number in the file — every dimension, coordinate
     * and counter — so a save would read fine but never compare equal.
     *
     * @throws IllegalArgumentException on NaN or infinity, which cannot appear in JSON.
     *     Validated state can never contain either, so reaching this means a bug upstream
     *     and silently writing {@code null} (as JavaScript does) would hide it.
     */
    public static String writeNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("cannot serialize non-finite number: " + value);
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e21) {
            // Whole number: print without a decimal point, as JavaScript does.
            // The 1e21 cut-off is where JavaScript itself switches to exponent form.
            long asLong = (long) value;
            if ((double) asLong == value) {
                return Long.toString(asLong);
            }
        }
        String text = Double.toString(value);
        // Java writes exponents as "1.0E-7"; JavaScript writes "1e-7". Our value ranges
        // (dimensions 1-1000, pixels 0-1e6, six decimal places) never reach exponent
        // form, but a hand-edited file could, and a wrong exponent would be a silent
        // corruption rather than a visible error.
        if (text.indexOf('E') >= 0) {
            text = text.replace("E", "e").replace(".0e", "e");
        }
        return text;
    }

    /** Writes a JSON string literal, escaping exactly what {@code JSON.stringify} escapes. */
    public static String writeString(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':  out.append("\\\"");  break;
                case '\\': out.append("\\\\");  break;
                case '\b': out.append("\\b");   break;
                case '\f': out.append("\\f");   break;
                case '\n': out.append("\\n");   break;
                case '\r': out.append("\\r");   break;
                case '\t': out.append("\\t");   break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        // Everything else, including non-ASCII, goes through as-is —
                        // again matching JSON.stringify, which does not escape it.
                        out.append(c);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }

    // ------------------------------------------------- JavaScript value rules

    /**
     * JavaScript's {@code Number(value)} conversion, which the original app's
     * {@code safeNum} depends on.
     *
     * <p>The surprising cases are all real and all load-bearing: {@code null} converts to
     * {@code 0} (so a {@code null} height passes through as 0 and then fails a
     * {@code >= 1} range check, landing on the default), an empty or whitespace-only
     * string is also {@code 0}, and a <em>missing</em> key is {@code NaN}. Java's own
     * parsing agrees with none of these, so it cannot be used here.
     *
     * @param value a parsed JSON value, or {@link #MISSING} for an absent key
     * @return the numeric value, or {@code NaN} where JavaScript yields {@code NaN}
     */
    public static double jsNumber(Object value) {
        if (value == MISSING) {
            return Double.NaN;          // undefined
        }
        if (value == null) {
            return 0.0;                 // Number(null) === 0
        }
        if (value instanceof Double) {
            return (Double) value;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1.0 : 0.0;
        }
        if (value instanceof String) {
            String trimmed = ((String) value).trim();
            if (trimmed.isEmpty()) {
                return 0.0;             // Number("") === 0, Number("   ") === 0
            }
            return parseJsNumericString(trimmed);
        }
        if (value instanceof List) {
            // Number([]) === 0, Number([5]) === 5, Number([1,2]) === NaN
            List<?> list = (List<?>) value;
            if (list.isEmpty()) {
                return 0.0;
            }
            if (list.size() == 1) {
                return jsNumber(list.get(0));
            }
            return Double.NaN;
        }
        return Double.NaN;              // Number({}) === NaN
    }

    /**
     * Marker for "this key was not present at all", which JavaScript would call
     * {@code undefined} and which {@link #jsNumber} must treat differently from
     * {@code null}. The original's {@code dragOrder} fallback turns on exactly that
     * distinction.
     */
    public static final Object MISSING = new Object() {
        @Override
        public String toString() {
            return "undefined";
        }
    };

    private static double parseJsNumericString(String trimmed) {
        try {
            if (trimmed.equals("Infinity") || trimmed.equals("+Infinity")) {
                return Double.POSITIVE_INFINITY;
            }
            if (trimmed.equals("-Infinity")) {
                return Double.NEGATIVE_INFINITY;
            }
            // JavaScript accepts 0x/0o/0b literals in strings; Java's parseDouble does not.
            if (trimmed.length() > 2 && (trimmed.charAt(0) == '0')) {
                char base = Character.toLowerCase(trimmed.charAt(1));
                if (base == 'x' || base == 'o' || base == 'b') {
                    int radix = base == 'x' ? 16 : base == 'o' ? 8 : 2;
                    return Long.parseLong(trimmed.substring(2), radix);
                }
            }
            // Java's parseDouble accepts things JavaScript rejects — a trailing 'd' or
            // 'f' suffix ("12f"), and hex floats. Reject those explicitly so "12f"
            // becomes NaN as it would in the browser.
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                boolean allowed = (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                        || c == '+' || c == '-';
                if (!allowed) {
                    return Double.NaN;
                }
            }
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Reads a key from a parsed object, returning {@link #MISSING} if it isn't there. */
    public static Object get(Map<String, Object> object, String key) {
        return object.containsKey(key) ? object.get(key) : MISSING;
    }
}

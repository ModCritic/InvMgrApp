package com.modcritic.invmgr.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {

    // ------------------------------------------------------------- parsing

    @Test
    void parsesTheBasicShapes() {
        Object parsed = Json.parse("{\"a\":1,\"b\":[true,false,null,\"x\"],\"c\":{\"d\":-2.5}}");
        Map<String, Object> map = assertInstanceOf(Map.class, parsed);
        assertEquals(1.0, map.get("a"));
        assertEquals(List.of(Boolean.TRUE, Boolean.FALSE), ((List<?>) map.get("b")).subList(0, 2));
        assertNull(((List<?>) map.get("b")).get(2));
        assertEquals("x", ((List<?>) map.get("b")).get(3));
        assertEquals(-2.5, ((Map<?, ?>) map.get("c")).get("d"));
    }

    @Test
    void parsesEmptyContainersAndWhitespace() {
        assertEquals(Map.of(), Json.parse("  {  }  "));
        assertEquals(List.of(), Json.parse("\n[\t]\r\n"));
    }

    @Test
    @DisplayName("string escapes decode, including \\u")
    void parsesStringEscapes() {
        assertEquals("quote\" slash\\ tab\t newline\n / é ☃",
                Json.parse("\"quote\\\" slash\\\\ tab\\t newline\\n \\/ \\u00e9 \\u2603\""));
    }

    @Test
    @DisplayName("a repeated key keeps the last value, as JavaScript does")
    void lastDuplicateKeyWins() {
        Map<?, ?> map = (Map<?, ?>) Json.parse("{\"a\":1,\"a\":2}");
        assertEquals(2.0, map.get("a"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{",                       // unterminated object
        "[1,2",                    // unterminated array
        "{\"a\":}",                // missing value
        "{a:1}",                   // unquoted key
        "{\"a\" 1}",               // missing colon
        "\"unterminated",          // unterminated string
        "\"bad escape \\q\"",      // invalid escape
        "tru",                     // truncated literal
        "{} extra",                // trailing content
        "",                        // empty input
        "01x2"                     // not a number
    })
    @DisplayName("malformed input throws JsonException with a message, never a raw crash")
    void rejectsMalformedInput(String bad) {
        Json.JsonException thrown = assertThrows(Json.JsonException.class, () -> Json.parse(bad));
        assertTrue(thrown.getMessage() != null && !thrown.getMessage().isEmpty(),
                "exception should carry a message explaining the problem");
    }

    // ------------------------------------------------------------- writing

    @Test
    @DisplayName("whole numbers write without a decimal point, like JavaScript")
    void writesNumbersTheJavaScriptWay() {
        assertEquals("12", Json.writeNumber(12));
        assertEquals("0", Json.writeNumber(0));
        assertEquals("0", Json.writeNumber(-0.0));
        assertEquals("96", Json.writeNumber(96.0));
        assertEquals("1000000", Json.writeNumber(1e6));
        assertEquals("-3", Json.writeNumber(-3.0));
        assertEquals("12.5", Json.writeNumber(12.5));
        assertEquals("4.86063", Json.writeNumber(4.86063));
        assertEquals("0.001", Json.writeNumber(0.001));
    }

    @Test
    void refusesToWriteNonFiniteNumbers() {
        assertThrows(IllegalArgumentException.class, () -> Json.writeNumber(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> Json.writeNumber(Double.POSITIVE_INFINITY));
    }

    @Test
    void escapesStringsTheSameWayStringifyDoes() {
        assertEquals("\"plain\"", Json.writeString("plain"));
        assertEquals("\"say \\\"hi\\\"\"", Json.writeString("say \"hi\""));
        assertEquals("\"back\\\\slash\"", Json.writeString("back\\slash"));
        assertEquals("\"line\\nbreak\\ttab\"", Json.writeString("line\nbreak\ttab"));
        assertEquals("\"\\u0001\"", Json.writeString(""));
        // Neither forward slashes nor non-ASCII are escaped by JSON.stringify.
        assertEquals("\"a/b café\"", Json.writeString("a/b café"));
    }

    @Test
    @DisplayName("write then parse gives back what went in")
    void stringRoundTrip() {
        String awkward = "tab\t quote\" backslash\\ newline\n café ☃";
        assertEquals(awkward, Json.parse(Json.writeString(awkward)));
    }

    // ------------------------------------------- JavaScript Number() rules

    @Test
    @DisplayName("jsNumber matches real JavaScript Number() on every case in the fixture")
    void jsNumberMatchesJavaScript() throws IOException {
        // Differential test. The expectations in golden/js-number-cases.expected.json were
        // produced by running Number() in node over fixtures/js-number-cases.json — see
        // tools/golden/original-loadstate.js. This asserts against the language's real
        // behaviour rather than against my understanding of it, which is the whole point:
        // the original app's safeNum sits directly on top of these conversions.
        List<?> cases = (List<?>) Json.parse(Fixtures.read("fixtures/js-number-cases.json"));
        List<?> expected = (List<?>) Json.parse(Fixtures.read("golden/js-number-cases.expected.json"));
        assertEquals(cases.size(), expected.size(), "fixture and golden are out of sync");

        for (int i = 0; i < cases.size(); i++) {
            Object input = cases.get(i);
            Object want = expected.get(i);
            double actual = Json.jsNumber(input);
            String label = "case " + i + " (" + input + ")";
            if ("NaN".equals(want)) {
                assertTrue(Double.isNaN(actual), label + " should be NaN but was " + actual);
            } else if ("Infinity".equals(want)) {
                assertEquals(Double.POSITIVE_INFINITY, actual, label);
            } else if ("-Infinity".equals(want)) {
                assertEquals(Double.NEGATIVE_INFINITY, actual, label);
            } else {
                assertEquals((Double) want, actual, 1e-12, label);
            }
        }
    }

    @Test
    @DisplayName("a missing key is undefined, which is not the same as null")
    void missingIsNotNull() {
        // This distinction is load-bearing: the original's dragOrder fallback fires on
        // "the key wasn't there", and a null dragOrder must NOT trigger it.
        assertTrue(Double.isNaN(Json.jsNumber(Json.MISSING)));
        assertEquals(0.0, Json.jsNumber(null));

        Map<String, Object> object = castToMap(Json.parse("{\"present\":null}"));
        assertEquals(null, Json.get(object, "present"));
        assertEquals(Json.MISSING, Json.get(object, "absent"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToMap(Object value) {
        return (Map<String, Object>) value;
    }
}

package de.ing.qa.ado;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader for the one file this plugin consumes: the ADO panel cache
 * written by {@code tools/ado-testcases.mjs}.
 *
 * <p>Why hand-rolled rather than a library: the plugin JAR is dropped into an
 * INGenious install as a plain artifact, with only {@code ingenious-api} available as
 * a provided dependency. Shipping a JSON library would mean shading it into the JAR
 * and risking a clash with whatever the host already loads. Reading one
 * well-known, machine-generated file does not justify that.
 *
 * <p>Parses into {@link Map}, {@link List}, {@link String}, {@link Double},
 * {@link Boolean} and {@code null}. Anything malformed raises {@link JsonException},
 * which callers turn into a message on screen — never into a crash.
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    /** Thrown on malformed input. Callers show the message; they never propagate it. */
    public static class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }

    public static Object parse(String text) {
        Json p = new Json(text == null ? "" : text);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (p.pos < p.src.length()) {
            throw new JsonException("Unerwartete Zeichen ab Position " + p.pos);
        }
        return value;
    }

    /** Escapes a string for embedding in JSON output (used when saving the selection). */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ parsing

    private Object readValue() {
        if (pos >= src.length()) {
            throw new JsonException("Unerwartetes Dateiende");
        }
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        Map<String, Object> out = new LinkedHashMap<>();
        pos++; // {
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw new JsonException("Objektschluessel erwartet an Position " + pos);
            }
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            out.put(key, readValue());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                throw new JsonException("',' oder '}' erwartet an Position " + (pos - 1));
            }
        }
    }

    private List<Object> readArray() {
        List<Object> out = new ArrayList<>();
        pos++; // [
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            skipWhitespace();
            out.add(readValue());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return out;
            }
            if (c != ',') {
                throw new JsonException("',' oder ']' erwartet an Position " + (pos - 1));
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char esc = next();
            switch (esc) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > src.length()) {
                        throw new JsonException("Abgeschnittene \\u-Sequenz");
                    }
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw new JsonException("Unbekannte Escape-Sequenz \\" + esc);
            }
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!src.startsWith(literal, pos)) {
            throw new JsonException("'" + literal + "' erwartet an Position " + pos);
        }
        pos += literal.length();
        return value;
    }

    private Double readNumber() {
        int start = pos;
        while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        if (start == pos) {
            throw new JsonException("Zahl erwartet an Position " + pos);
        }
        try {
            return Double.valueOf(src.substring(start, pos));
        } catch (NumberFormatException ex) {
            throw new JsonException("Ungueltige Zahl: " + src.substring(start, pos));
        }
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new JsonException("Unerwartetes Dateiende");
        }
        return src.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) {
            throw new JsonException("'" + c + "' erwartet an Position " + (pos - 1));
        }
    }
}

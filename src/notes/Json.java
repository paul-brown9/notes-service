package notes;

import java.util.*;

/** Minimal JSON reader/writer so the service has zero external dependencies. */
final class Json {
    private Json() {}

    static Object parse(String s) {
        Parser p = new Parser(s);
        Object v = p.value(0);
        p.ws();
        if (p.i != s.length()) throw p.err("Trailing data");
        return v;
    }

    static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        w(o, sb);
        return sb.toString();
    }

    private static void w(Object o, StringBuilder sb) {
        if (o == null) sb.append("null");
        else if (o instanceof String s) str(s, sb);
        else if (o instanceof Number || o instanceof Boolean) sb.append(o);
        else if (o instanceof Enum<?> e) str(e.name(), sb);
        else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                str(String.valueOf(e.getKey()), sb);
                sb.append(':');
                w(e.getValue(), sb);
            }
            sb.append('}');
        } else if (o instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object x : c) {
                if (!first) sb.append(',');
                first = false;
                w(x, sb);
            }
            sb.append(']');
        } else throw new IllegalArgumentException("Unsupported type " + o.getClass());
    }

    private static void str(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) { this.s = s; }

        IllegalArgumentException err(String m) { return new IllegalArgumentException(m + " at " + i); }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        char peek() {
            if (i >= s.length()) throw err("Unexpected end");
            return s.charAt(i);
        }

        Object value(int depth) {
            if (depth > 64) throw err("Nesting too deep");
            ws();
            return switch (peek()) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> lit("true", Boolean.TRUE);
                case 'f' -> lit("false", Boolean.FALSE);
                case 'n' -> lit("null", null);
                default -> number();
            };
        }

        Object lit(String word, Object v) {
            if (!s.startsWith(word, i)) throw err("Bad literal");
            i += word.length();
            return v;
        }

        Map<String, Object> object(int depth) {
            i++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                if (peek() != '"') throw err("Expected key");
                String k = string();
                ws();
                if (peek() != ':') throw err("Expected ':'");
                i++;
                m.put(k, value(depth + 1));
                ws();
                char c = peek();
                i++;
                if (c == ',') continue;
                if (c == '}') return m;
                throw err("Expected ',' or '}'");
            }
        }

        List<Object> array(int depth) {
            i++;
            List<Object> l = new ArrayList<>();
            ws();
            if (peek() == ']') { i++; return l; }
            while (true) {
                l.add(value(depth + 1));
                ws();
                char c = peek();
                i++;
                if (c == ',') continue;
                if (c == ']') return l;
                throw err("Expected ',' or ']'");
            }
        }

        String string() {
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err("Unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw err("Bad escape");
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '"', '\\', '/' -> sb.append(e);
                    case 'u' -> {
                        if (i + 4 > s.length()) throw err("Bad unicode escape");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw err("Bad escape");
                }
            }
        }

        Object number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw err("Unexpected character");
            String t = s.substring(st, i);
            try {
                if (t.matches("-?\\d+")) return Long.parseLong(t);
                return Double.parseDouble(t);
            } catch (NumberFormatException e) {
                throw err("Bad number");
            }
        }
    }
}

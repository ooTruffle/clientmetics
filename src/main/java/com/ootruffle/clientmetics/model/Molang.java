package com.ootruffle.clientmetics.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * A small Molang interpreter, enough for cosmetic animations: numbers, {@code + - * /}, comparisons,
 * {@code && || !}, {@code ?:} and {@code ??}, parentheses, {@code math.*} functions (angles in
 * degrees, as Molang uses), variables ({@code query/q}, {@code variable/v}, {@code temp/t},
 * {@code context/c}, {@code option}), assignments and {@code return} in {@code ;}-separated
 * statements, and helper functions loaded from the install's {@code functions.molang}. Anything
 * unknown reads as 0, so an unsupported query freezes a motion rather than breaking the model.
 */
public final class Molang {

    /** Variable values for one evaluation; names are normalised ({@code q.x} and {@code query.x} are the same). */
    public static final class Scope {
        private final Map<String, Double> values = new HashMap<>();

        public Scope set(String name, double value) {
            values.put(normalise(name), value);
            return this;
        }

        public double get(String name) {
            final Double v = values.get(name);
            return v == null ? 0 : v;
        }

        public boolean has(String name) {
            return values.containsKey(name);
        }

        /** Clears temp variables between evaluations, as Molang does. */
        void clearTemps() {
            values.keySet().removeIf(k -> k.startsWith("temp."));
        }
    }

    @FunctionalInterface
    public interface Expr {
        double eval(Scope scope);
    }

    public static final Expr ZERO = s -> 0;

    private static final Random RANDOM = new Random();
    private static final Map<String, Expr> CACHE = new HashMap<>();
    private static final int MAX_CALL_DEPTH = 32;

    /** A function from {@code functions.molang}: {@code name(a.x, a.y): |- body}. */
    private static final class UserFunction {
        final String[] params;
        final Expr body;

        UserFunction(String[] params, Expr body) {
            this.params = params;
            this.body = body;
        }
    }

    private static final Map<String, UserFunction> FUNCTIONS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ThreadLocal<int[]> CALL_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private Molang() {}

    /**
     * Loads helper functions (Lunar keeps them in {@code cosmetics/functions.molang}), in the form
     * <pre>
     * lunar.sinw(a.frq, a.amp): |-
     *    return math.sin(q.life_time * a.frq) * a.amp;
     * </pre>
     * Returns how many were loaded.
     */
    public static int registerFunctions(String text) {
        final java.util.regex.Pattern header = java.util.regex.Pattern.compile("^([A-Za-z_][\\w.]*)\\(([^)]*)\\)\\s*:\\s*\\|-?\\s*$");
        String name = null;
        String[] params = null;
        final StringBuilder body = new StringBuilder();
        int count = 0;
        for (String line : (text + "\n").split("\r?\n")) {
            final java.util.regex.Matcher m = header.matcher(line);
            final boolean indented = !line.isEmpty() && Character.isWhitespace(line.charAt(0));
            if (m.matches() || (!indented && !line.trim().isEmpty())) {
                if (name != null && defineFunction(name, params, body.toString())) {
                    count++;
                }
                name = null;
                body.setLength(0);
                if (m.matches()) {
                    name = normalise(m.group(1));
                    final String raw = m.group(2).trim();
                    params = raw.isEmpty() ? new String[0] : raw.split("\\s*,\\s*");
                    for (int i = 0; i < params.length; i++) {
                        params[i] = normalise(params[i]);
                    }
                }
            } else if (name != null) {
                body.append(line).append('\n');
            }
        }
        if (name != null && defineFunction(name, params, body.toString())) {
            count++;
        }
        return count;
    }

    private static boolean defineFunction(String name, String[] params, String body) {
        try {
            FUNCTIONS.put(name, new UserFunction(params, new Parser(body).program()));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static double callUser(String name, Expr[] args, Scope scope) {
        final UserFunction f = FUNCTIONS.get(name);
        final int[] depth = CALL_DEPTH.get();
        if (f == null || depth[0] >= MAX_CALL_DEPTH) {
            return 0;
        }
        final double[] values = new double[f.params.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = i < args.length ? args[i].eval(scope) : 0;
        }
        final Double[] saved = new Double[f.params.length];
        for (int i = 0; i < values.length; i++) {
            saved[i] = scope.values.get(f.params[i]);
            scope.values.put(f.params[i], values[i]);
        }
        depth[0]++;
        try {
            return f.body.eval(scope);
        } finally {
            depth[0]--;
            for (int i = 0; i < saved.length; i++) {
                if (saved[i] == null) {
                    scope.values.remove(f.params[i]);
                } else {
                    scope.values.put(f.params[i], saved[i]);
                }
            }
        }
    }

    /** A number, or a Molang string to compile. Unparseable input becomes a constant 0. */
    public static synchronized Expr compile(String source) {
        return CACHE.computeIfAbsent(source, src -> {
            try {
                return new Parser(src).program();
            } catch (RuntimeException e) {
                return ZERO;
            }
        });
    }

    public static Expr constant(double value) {
        return s -> value;
    }

    static String normalise(String name) {
        final String n = name.toLowerCase(Locale.ROOT);
        final int dot = n.indexOf('.');
        if (dot < 0) {
            return n;
        }
        final String head = n.substring(0, dot);
        final String rest = n.substring(dot);
        switch (head) {
            case "q":
                return "query" + rest;
            case "v":
                return "variable" + rest;
            case "t":
                return "temp" + rest;
            case "c":
                return "context" + rest;
            case "arg":
                return "a" + rest;
            default:
                return n;
        }
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        Expr program() {
            final List<Expr> statements = new ArrayList<>();
            final List<Boolean> returns = new ArrayList<>();
            while (true) {
                skipSpace();
                if (pos >= src.length()) {
                    break;
                }
                if (peek(';')) {
                    pos++;
                    continue;
                }
                boolean isReturn = false;
                if (word("return")) {
                    isReturn = true;
                }
                statements.add(assignment());
                returns.add(isReturn);
                skipSpace();
                if (pos < src.length() && !peek(';')) {
                    throw new IllegalArgumentException("unexpected '" + src.charAt(pos) + "'");
                }
            }
            if (statements.isEmpty()) {
                return ZERO;
            }
            if (statements.size() == 1) {
                return statements.get(0);
            }
            final Expr[] list = statements.toArray(new Expr[0]);
            final Boolean[] ret = returns.toArray(new Boolean[0]);
            final boolean anyReturn = returns.contains(true);
            return scope -> {
                double last = 0;
                for (int i = 0; i < list.length; i++) {
                    last = list[i].eval(scope);
                    if (ret[i]) {
                        scope.clearTemps();
                        return last;
                    }
                }
                scope.clearTemps();
                return anyReturn ? 0 : last;
            };
        }

        private Expr assignment() {
            final int start = pos;
            skipSpace();
            final String name = identifier();
            if (name != null) {
                skipSpace();
                if (peek('=') && !(pos + 1 < src.length() && src.charAt(pos + 1) == '=')) {
                    pos++;
                    final Expr value = ternary();
                    final String key = normalise(name);
                    return s -> {
                        final double v = value.eval(s);
                        s.values.put(key, v);
                        return v;
                    };
                }
            }
            pos = start;
            return ternary();
        }

        private Expr ternary() {
            final Expr cond = coalesce();
            skipSpace();
            if (peek('?') && !(pos + 1 < src.length() && src.charAt(pos + 1) == '?')) {
                pos++;
                // Branches may assign: "cond ? v.x = 1 : 0".
                final Expr a = assignment();
                skipSpace();
                if (peek(':')) {
                    pos++;
                    final Expr b = assignment();
                    return s -> cond.eval(s) != 0 ? a.eval(s) : b.eval(s);
                }
                return s -> cond.eval(s) != 0 ? a.eval(s) : 0;
            }
            return cond;
        }

        private Expr coalesce() {
            Expr left = or();
            while (match("??")) {
                final Expr l = left;
                final Expr r = or();
                left = s -> {
                    final double v = l.eval(s);
                    return v != 0 ? v : r.eval(s);
                };
            }
            return left;
        }

        private Expr or() {
            Expr left = and();
            while (match("||")) {
                final Expr l = left;
                final Expr r = and();
                left = s -> l.eval(s) != 0 || r.eval(s) != 0 ? 1 : 0;
            }
            return left;
        }

        private Expr and() {
            Expr left = equality();
            while (match("&&")) {
                final Expr l = left;
                final Expr r = equality();
                left = s -> l.eval(s) != 0 && r.eval(s) != 0 ? 1 : 0;
            }
            return left;
        }

        private Expr equality() {
            Expr left = comparison();
            while (true) {
                final Expr l = left;
                if (match("==")) {
                    final Expr r = comparison();
                    left = s -> l.eval(s) == r.eval(s) ? 1 : 0;
                } else if (match("!=")) {
                    final Expr r = comparison();
                    left = s -> l.eval(s) != r.eval(s) ? 1 : 0;
                } else {
                    return left;
                }
            }
        }

        private Expr comparison() {
            Expr left = additive();
            while (true) {
                final Expr l = left;
                if (match("<=")) {
                    final Expr r = additive();
                    left = s -> l.eval(s) <= r.eval(s) ? 1 : 0;
                } else if (match(">=")) {
                    final Expr r = additive();
                    left = s -> l.eval(s) >= r.eval(s) ? 1 : 0;
                } else if (match("<")) {
                    final Expr r = additive();
                    left = s -> l.eval(s) < r.eval(s) ? 1 : 0;
                } else if (match(">")) {
                    final Expr r = additive();
                    left = s -> l.eval(s) > r.eval(s) ? 1 : 0;
                } else {
                    return left;
                }
            }
        }

        private Expr additive() {
            Expr left = multiplicative();
            while (true) {
                final Expr l = left;
                if (match("+")) {
                    final Expr r = multiplicative();
                    left = s -> l.eval(s) + r.eval(s);
                } else if (match("-")) {
                    final Expr r = multiplicative();
                    left = s -> l.eval(s) - r.eval(s);
                } else {
                    return left;
                }
            }
        }

        private Expr multiplicative() {
            Expr left = unary();
            while (true) {
                final Expr l = left;
                if (match("*")) {
                    final Expr r = unary();
                    left = s -> l.eval(s) * r.eval(s);
                } else if (match("/")) {
                    final Expr r = unary();
                    left = s -> {
                        final double d = r.eval(s);
                        return d == 0 ? 0 : l.eval(s) / d;
                    };
                } else {
                    return left;
                }
            }
        }

        private Expr unary() {
            if (match("-")) {
                final Expr e = unary();
                return s -> -e.eval(s);
            }
            if (match("+")) {
                return unary();
            }
            if (match("!")) {
                final Expr e = unary();
                return s -> e.eval(s) == 0 ? 1 : 0;
            }
            return primary();
        }

        private Expr primary() {
            skipSpace();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("unexpected end");
            }
            final char c = src.charAt(pos);
            if (c == '(') {
                pos++;
                final Expr e = ternary();
                expect(')');
                return e;
            }
            if (c == '{') {
                // A block: run it, value 0.
                pos++;
                final int start = pos;
                int depth = 1;
                while (pos < src.length() && depth > 0) {
                    if (src.charAt(pos) == '{') {
                        depth++;
                    } else if (src.charAt(pos) == '}') {
                        depth--;
                    }
                    pos++;
                }
                final Expr inner = new Parser(src.substring(start, Math.max(start, pos - 1))).program();
                return s -> {
                    inner.eval(s);
                    return 0;
                };
            }
            if (c == '\'') {
                final int end = src.indexOf('\'', pos + 1);
                pos = end < 0 ? src.length() : end + 1;
                return ZERO;
            }
            if (Character.isDigit(c) || c == '.') {
                final int start = pos;
                while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                    pos++;
                }
                if (pos < src.length() && (src.charAt(pos) == 'f' || src.charAt(pos) == 'F')) {
                    pos++;
                    return constant(Double.parseDouble(src.substring(start, pos - 1)));
                }
                return constant(Double.parseDouble(src.substring(start, pos)));
            }
            final String name = identifier();
            if (name == null) {
                throw new IllegalArgumentException("unexpected '" + c + "'");
            }
            final String key = normalise(name);
            skipSpace();
            if (peek('(')) {
                pos++;
                final List<Expr> args = new ArrayList<>();
                skipSpace();
                if (!peek(')')) {
                    do {
                        args.add(ternary());
                    } while (match(","));
                }
                expect(')');
                return function(key, args.toArray(new Expr[0]));
            }
            switch (key) {
                case "math.pi":
                    return constant(Math.PI);
                case "true":
                    return constant(1);
                case "false":
                    return constant(0);
                default:
                    return s -> s.get(key);
            }
        }

        private static Expr function(String name, Expr[] a) {
            switch (name) {
                case "math.cos":
                    return s -> Math.cos(Math.toRadians(arg(a, 0, s)));
                case "math.sin":
                    return s -> Math.sin(Math.toRadians(arg(a, 0, s)));
                case "math.atan":
                    return s -> Math.toDegrees(Math.atan(arg(a, 0, s)));
                case "math.atan2":
                    return s -> Math.toDegrees(Math.atan2(arg(a, 0, s), arg(a, 1, s)));
                case "math.abs":
                    return s -> Math.abs(arg(a, 0, s));
                case "math.clamp":
                    return s -> Math.max(arg(a, 1, s), Math.min(arg(a, 2, s), arg(a, 0, s)));
                case "math.min":
                    return s -> Math.min(arg(a, 0, s), arg(a, 1, s));
                case "math.max":
                    return s -> Math.max(arg(a, 0, s), arg(a, 1, s));
                case "math.mod":
                    return s -> {
                        final double d = arg(a, 1, s);
                        return d == 0 ? 0 : arg(a, 0, s) % d;
                    };
                case "math.floor":
                    return s -> Math.floor(arg(a, 0, s));
                case "math.ceil":
                    return s -> Math.ceil(arg(a, 0, s));
                case "math.round":
                    return s -> Math.round(arg(a, 0, s));
                case "math.trunc":
                    return s -> (long) arg(a, 0, s);
                case "math.sqrt":
                    return s -> Math.sqrt(Math.max(0, arg(a, 0, s)));
                case "math.pow":
                    return s -> Math.pow(arg(a, 0, s), arg(a, 1, s));
                case "math.exp":
                    return s -> Math.exp(arg(a, 0, s));
                case "math.ln":
                    return s -> Math.log(arg(a, 0, s));
                case "math.lerp":
                    return s -> {
                        final double t = arg(a, 2, s);
                        return arg(a, 0, s) + (arg(a, 1, s) - arg(a, 0, s)) * t;
                    };
                case "math.lerprotate":
                    return s -> {
                        final double from = arg(a, 0, s);
                        double diff = (arg(a, 1, s) - from) % 360;
                        if (diff > 180) {
                            diff -= 360;
                        } else if (diff < -180) {
                            diff += 360;
                        }
                        return from + diff * arg(a, 2, s);
                    };
                case "math.random":
                    return s -> {
                        final double lo = arg(a, 0, s);
                        return lo + RANDOM.nextDouble() * (arg(a, 1, s) - lo);
                    };
                case "math.random_integer":
                    return s -> {
                        final long lo = Math.round(arg(a, 0, s));
                        final long hi = Math.round(arg(a, 1, s));
                        return hi <= lo ? lo : lo + RANDOM.nextInt((int) (hi - lo + 1));
                    };
                case "math.hermite_blend":
                    return s -> {
                        final double t = arg(a, 0, s);
                        return 3 * t * t - 2 * t * t * t;
                    };
                default:
                    // A helper from functions.molang, looked up when it runs (it may load later).
                    return s -> callUser(name, a, s);
            }
        }

        private static double arg(Expr[] args, int i, Scope s) {
            return i < args.length ? args[i].eval(s) : 0;
        }

        private String identifier() {
            skipSpace();
            final int start = pos;
            while (pos < src.length()) {
                final char c = src.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_' || (c == '.' && pos > start)) {
                    pos++;
                } else {
                    break;
                }
            }
            if (pos == start || Character.isDigit(src.charAt(start))) {
                pos = start;
                return null;
            }
            return src.substring(start, pos);
        }

        private boolean word(String w) {
            skipSpace();
            if (src.regionMatches(true, pos, w, 0, w.length())
                    && (pos + w.length() >= src.length() || !Character.isLetterOrDigit(src.charAt(pos + w.length())))) {
                pos += w.length();
                return true;
            }
            return false;
        }

        private boolean match(String op) {
            skipSpace();
            if (src.startsWith(op, pos)) {
                pos += op.length();
                return true;
            }
            return false;
        }

        private boolean peek(char c) {
            return pos < src.length() && src.charAt(pos) == c;
        }

        private void expect(char c) {
            skipSpace();
            if (!peek(c)) {
                throw new IllegalArgumentException("expected " + c);
            }
            pos++;
        }

        private void skipSpace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
    }

}

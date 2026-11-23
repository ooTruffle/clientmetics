package com.ootruffle.clientmetics.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One Bedrock {@code .anim.json} animation: per bone, rotation / position / scale channels that are
 * either one Molang vector or keyframes (linearly interpolated; Catmull-Rom is drawn as linear).
 */
public final class GeoAnimation {

    /** A bone's pose offsets for one moment: rotation in degrees, position in pixels, scale as a multiplier. */
    public static final class Pose {
        public final double[] rotation = new double[3];
        public final double[] position = new double[3];
        public final double[] scale = {1, 1, 1};
    }

    private static final class Vec {
        final Molang.Expr x;
        final Molang.Expr y;
        final Molang.Expr z;

        Vec(Molang.Expr x, Molang.Expr y, Molang.Expr z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        void eval(Molang.Scope s, double[] out) {
            out[0] = x.eval(s);
            out[1] = y.eval(s);
            out[2] = z.eval(s);
        }
    }

    /** A channel: a single vector, or keyframes by time (seconds). */
    private static final class Channel {
        final Vec constant;
        final double[] times;
        final Vec[] pre;
        final Vec[] post;

        Channel(Vec constant) {
            this.constant = constant;
            this.times = null;
            this.pre = null;
            this.post = null;
        }

        Channel(double[] times, Vec[] pre, Vec[] post) {
            this.constant = null;
            this.times = times;
            this.pre = pre;
            this.post = post;
        }

        void eval(Molang.Scope s, double time, double[] out) {
            if (constant != null) {
                constant.eval(s, out);
                return;
            }
            if (time <= times[0]) {
                post[0].eval(s, out);
                return;
            }
            for (int i = 0; i < times.length - 1; i++) {
                if (time < times[i + 1]) {
                    final double t = (time - times[i]) / (times[i + 1] - times[i]);
                    final double[] a = new double[3];
                    final double[] b = new double[3];
                    post[i].eval(s, a);
                    pre[i + 1].eval(s, b);
                    for (int k = 0; k < 3; k++) {
                        out[k] = a[k] + (b[k] - a[k]) * t;
                    }
                    return;
                }
            }
            post[times.length - 1].eval(s, out);
        }

        double lastTime() {
            return times == null ? 0 : times[times.length - 1];
        }
    }

    private static final class BoneChannels {
        Channel rotation;
        Channel position;
        Channel scale;
    }

    private final Map<String, BoneChannels> bones = new HashMap<>();
    private final double length;
    private final boolean loop;

    private GeoAnimation(JsonObject json) {
        double longest = 0;
        final JsonObject bonesJson = json.has("bones") && json.get("bones").isJsonObject() ? json.getAsJsonObject("bones") : new JsonObject();
        for (Map.Entry<String, JsonElement> bone : bonesJson.entrySet()) {
            if (!bone.getValue().isJsonObject()) {
                continue;
            }
            final JsonObject b = bone.getValue().getAsJsonObject();
            final BoneChannels channels = new BoneChannels();
            channels.rotation = channel(b.get("rotation"), 0);
            channels.position = channel(b.get("position"), 0);
            channels.scale = channel(b.get("scale"), 1);
            for (Channel c : new Channel[]{channels.rotation, channels.position, channels.scale}) {
                if (c != null) {
                    longest = Math.max(longest, c.lastTime());
                }
            }
            bones.put(bone.getKey().toLowerCase(), channels);
        }
        this.length = json.has("animation_length") ? json.get("animation_length").getAsDouble() : longest;
        final JsonElement loopJson = json.get("loop");
        this.loop = loopJson != null && loopJson.isJsonPrimitive()
                && (loopJson.getAsJsonPrimitive().isBoolean() ? loopJson.getAsBoolean() : "true".equals(loopJson.getAsString()));
    }

    /** Every animation in an {@code .anim.json}, by name. */
    public static Map<String, GeoAnimation> parseFile(JsonObject root) {
        final Map<String, GeoAnimation> out = new HashMap<>();
        if (root.has("animations") && root.get("animations").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("animations").entrySet()) {
                if (e.getValue().isJsonObject()) {
                    out.put(e.getKey(), new GeoAnimation(e.getValue().getAsJsonObject()));
                }
            }
        }
        return out;
    }

    /** Fills {@code pose} for {@code bone} at {@code animTime} seconds; false if the animation doesn't move it. */
    public boolean pose(String bone, double animTime, Molang.Scope scope, Pose pose) {
        final BoneChannels c = bones.get(bone.toLowerCase());
        if (c == null) {
            return false;
        }
        double time = animTime;
        if (length > 0) {
            time = loop ? animTime % length : Math.min(animTime, length);
        }
        if (c.rotation != null) {
            c.rotation.eval(scope, time, pose.rotation);
        }
        if (c.position != null) {
            c.position.eval(scope, time, pose.position);
        }
        if (c.scale != null) {
            c.scale.eval(scope, time, pose.scale);
        }
        return true;
    }

    private static Channel channel(JsonElement e, double def) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e.isJsonPrimitive() || e.isJsonArray()) {
            return new Channel(vec(e, def));
        }
        final JsonObject o = e.getAsJsonObject();
        if (o.has("vector")) {
            return new Channel(vec(o.get("vector"), def));
        }
        final TreeMap<Double, JsonElement> frames = new TreeMap<>();
        for (Map.Entry<String, JsonElement> f : o.entrySet()) {
            try {
                frames.put(Double.parseDouble(f.getKey()), f.getValue());
            } catch (NumberFormatException ignored) {
                // Not a keyframe time.
            }
        }
        if (frames.isEmpty()) {
            return null;
        }
        final double[] times = new double[frames.size()];
        final Vec[] pre = new Vec[frames.size()];
        final Vec[] post = new Vec[frames.size()];
        int i = 0;
        for (Map.Entry<Double, JsonElement> f : frames.entrySet()) {
            times[i] = f.getKey();
            final JsonElement v = f.getValue();
            if (v.isJsonObject()) {
                final JsonObject kf = v.getAsJsonObject();
                final JsonElement postJson = kf.has("post") ? kf.get("post") : kf.has("vector") ? kf.get("vector") : kf.get("pre");
                final JsonElement preJson = kf.has("pre") ? kf.get("pre") : postJson;
                post[i] = vec(unwrapVector(postJson), def);
                pre[i] = vec(unwrapVector(preJson), def);
            } else {
                post[i] = vec(v, def);
                pre[i] = post[i];
            }
            i++;
        }
        return new Channel(times, pre, post);
    }

    private static JsonElement unwrapVector(JsonElement e) {
        if (e != null && e.isJsonObject() && e.getAsJsonObject().has("vector")) {
            return e.getAsJsonObject().get("vector");
        }
        return e;
    }

    private static Vec vec(JsonElement e, double def) {
        if (e == null || e.isJsonNull()) {
            final Molang.Expr d = Molang.constant(def);
            return new Vec(d, d, d);
        }
        if (e.isJsonArray()) {
            final JsonArray a = e.getAsJsonArray();
            final List<Molang.Expr> parts = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                parts.add(i < a.size() ? expr(a.get(i), def) : Molang.constant(def));
            }
            return new Vec(parts.get(0), parts.get(1), parts.get(2));
        }
        final Molang.Expr single = expr(e, def);
        return new Vec(single, single, single);
    }

    private static Molang.Expr expr(JsonElement e, double def) {
        if (e == null || !e.isJsonPrimitive()) {
            return Molang.constant(def);
        }
        if (e.getAsJsonPrimitive().isNumber()) {
            return Molang.constant(e.getAsDouble());
        }
        final String s = e.getAsString().trim();
        try {
            return Molang.constant(Double.parseDouble(s));
        } catch (NumberFormatException ignored) {
            return Molang.compile(s);
        }
    }

}

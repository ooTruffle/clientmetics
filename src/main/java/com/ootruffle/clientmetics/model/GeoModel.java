package com.ootruffle.clientmetics.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * A Bedrock {@code .geo.json} model (format 1.12), drawn with GeckoLib's conventions so Lunar's
 * GeckoLib cosmetics come out the way they were authored: X is mirrored into Java's model space,
 * X and Y rotations are negated, and rotations apply Z, then Y, then X about each bone's pivot.
 * Units are pixels; the caller scales by 1/16. Each bone's cubes are baked into a display list on
 * first draw.
 */
public final class GeoModel {

    public static final class Bone {
        public final String name;
        final String parentName;
        /** Pivot in Java space (X already mirrored). */
        final float[] pivot;
        /** Base rotation in degrees, in Bedrock's sign convention. */
        final float[] rotation;
        final List<float[]> quads = new ArrayList<>();
        final List<Bone> children = new ArrayList<>();
        int displayList = -1;
        boolean root;

        /** A top-level bone (no parent, or a parent the file doesn't define). */
        public boolean isRoot() {
            return root;
        }

        Bone(String name, String parentName, float[] pivot, float[] rotation) {
            this.name = name;
            this.parentName = parentName;
            this.pivot = pivot;
            this.rotation = rotation;
        }
    }

    /** Per-bone hooks while drawing: animation pose and attachment to the player's limbs. */
    public interface BoneHook {
        /** Called in the parent bone's space, before this bone's own transform. */
        void beforeBone(Bone bone);

        /** The animation pose for this bone, or null for none. */
        GeoAnimation.Pose pose(Bone bone);

        /** Whether to leave this bone, and everything under it, out. */
        default boolean skip(Bone bone) {
            return false;
        }

        /**
         * Whether this bone's cubes, and everything under it, are drawn. A bone that isn't still
         * moves its children, so a subtree can be drawn on its own (an arm in first person).
         */
        default boolean draws(Bone bone) {
            return true;
        }
    }

    public final float textureWidth;
    public final float textureHeight;
    private final List<Bone> roots = new ArrayList<>();

    private GeoModel(float textureWidth, float textureHeight) {
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
    }

    public static GeoModel parse(JsonObject root) {
        final JsonArray geometries = root.getAsJsonArray("minecraft:geometry");
        if (geometries == null || geometries.size() == 0) {
            throw new IllegalArgumentException("no minecraft:geometry");
        }
        final JsonObject geo = geometries.get(0).getAsJsonObject();
        final JsonObject desc = geo.has("description") ? geo.getAsJsonObject("description") : new JsonObject();
        final GeoModel model = new GeoModel(
                desc.has("texture_width") ? desc.get("texture_width").getAsFloat() : 64,
                desc.has("texture_height") ? desc.get("texture_height").getAsFloat() : 64);

        final Map<String, Bone> byName = new HashMap<>();
        final List<Bone> all = new ArrayList<>();
        if (geo.has("bones")) {
            for (JsonElement e : geo.getAsJsonArray("bones")) {
                final JsonObject b = e.getAsJsonObject();
                final float[] pivot = vec(b.get("pivot"), 0);
                pivot[0] = -pivot[0];
                final Bone bone = new Bone(b.get("name").getAsString(),
                        b.has("parent") ? b.get("parent").getAsString() : null, pivot, vec(b.get("rotation"), 0));
                if (b.has("cubes")) {
                    for (JsonElement c : b.getAsJsonArray("cubes")) {
                        model.addCube(bone, c.getAsJsonObject());
                    }
                }
                byName.put(bone.name, bone);
                all.add(bone);
            }
        }
        for (Bone bone : all) {
            final Bone parent = bone.parentName == null ? null : byName.get(bone.parentName);
            if (parent != null && parent != bone) {
                parent.children.add(bone);
            } else {
                bone.root = true;
                model.roots.add(bone);
            }
        }
        return model;
    }

    /** Whether the model has nothing to draw (only locators, as particle effects have). */
    public boolean isEmpty() {
        final List<Bone> stack = new ArrayList<>(roots);
        while (!stack.isEmpty()) {
            final Bone b = stack.remove(stack.size() - 1);
            if (!b.quads.isEmpty()) {
                return false;
            }
            stack.addAll(b.children);
        }
        return true;
    }

    public void render(BoneHook hook) {
        for (Bone bone : roots) {
            renderBone(bone, hook, false);
        }
    }

    private void renderBone(Bone bone, BoneHook hook, boolean parentDrawn) {
        if (hook.skip(bone)) {
            return;
        }
        final boolean drawn = parentDrawn || hook.draws(bone);
        GlStateManager.pushMatrix();
        hook.beforeBone(bone);
        final GeoAnimation.Pose pose = hook.pose(bone);
        double rx = bone.rotation[0];
        double ry = bone.rotation[1];
        double rz = bone.rotation[2];
        if (pose != null) {
            GlStateManager.translate(-pose.position[0], pose.position[1], pose.position[2]);
            rx += pose.rotation[0];
            ry += pose.rotation[1];
            rz += pose.rotation[2];
        }
        GlStateManager.translate(bone.pivot[0], bone.pivot[1], bone.pivot[2]);
        if (rz != 0) {
            GlStateManager.rotate((float) rz, 0, 0, 1);
        }
        if (ry != 0) {
            GlStateManager.rotate((float) -ry, 0, 1, 0);
        }
        if (rx != 0) {
            GlStateManager.rotate((float) -rx, 1, 0, 0);
        }
        if (pose != null && (pose.scale[0] != 1 || pose.scale[1] != 1 || pose.scale[2] != 1)) {
            GlStateManager.scale(pose.scale[0], pose.scale[1], pose.scale[2]);
        }
        GlStateManager.translate(-bone.pivot[0], -bone.pivot[1], -bone.pivot[2]);
        final boolean hidden = pose != null && pose.scale[0] == 0 && pose.scale[1] == 0 && pose.scale[2] == 0;
        if (!hidden) {
            if (drawn && !bone.quads.isEmpty()) {
                if (bone.displayList < 0) {
                    bone.displayList = compile(bone);
                }
                GlStateManager.callList(bone.displayList);
            }
            for (Bone child : bone.children) {
                renderBone(child, hook, drawn);
            }
        }
        GlStateManager.popMatrix();
    }

    /** Frees the display lists (render thread). */
    public void delete() {
        final List<Bone> stack = new ArrayList<>(roots);
        while (!stack.isEmpty()) {
            final Bone b = stack.remove(stack.size() - 1);
            if (b.displayList >= 0) {
                GLAllocation.deleteDisplayLists(b.displayList);
                b.displayList = -1;
            }
            stack.addAll(b.children);
        }
    }

    private static int compile(Bone bone) {
        final int list = GLAllocation.generateDisplayLists(1);
        GL11.glNewList(list, GL11.GL_COMPILE);
        final Tessellator tess = Tessellator.getInstance();
        final WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
        for (float[] q : bone.quads) {
            // q: 4 x (x, y, z, u, v) then normal x, y, z
            for (int i = 0; i < 4; i++) {
                final int o = i * 5;
                wr.pos(q[o], q[o + 1], q[o + 2]).tex(q[o + 3], q[o + 4]).normal(q[20], q[21], q[22]).endVertex();
            }
        }
        tess.draw();
        GL11.glEndList();
        return list;
    }

    // ---- cube building ----

    private static final String[] FACES = {"north", "south", "east", "west", "up", "down"};

    private void addCube(Bone bone, JsonObject c) {
        final float[] origin = vec(c.get("origin"), 0);
        final float[] size = vec(c.get("size"), 0);
        final float inflate = c.has("inflate") ? c.get("inflate").getAsFloat() : 0;
        final boolean mirror = c.has("mirror") && c.get("mirror").getAsBoolean();
        // Java space: X mirrored.
        final float x1 = -(origin[0] + size[0]) - inflate;
        final float x2 = -origin[0] + inflate;
        final float y1 = origin[1] - inflate;
        final float y2 = origin[1] + size[1] + inflate;
        final float z1 = origin[2] - inflate;
        final float z2 = origin[2] + size[2] + inflate;

        final float[] cubePivot = vec(c.get("pivot"), 0);
        cubePivot[0] = -cubePivot[0];
        final float[] cubeRot = vec(c.get("rotation"), 0);

        final Map<String, float[]> uvs = faceUvs(c.get("uv"), size, mirror);
        for (String face : FACES) {
            final float[] uv = uvs.get(face);
            if (uv == null) {
                continue;
            }
            final float[][] corners = corners(face, x1, x2, y1, y2, z1, z2);
            final float[] normal = normal(face);
            final float u0 = uv[0] / textureWidth;
            final float v0 = uv[1] / textureHeight;
            final float u1 = (uv[0] + uv[2]) / textureWidth;
            final float v1 = (uv[1] + uv[3]) / textureHeight;
            final float[][] tex = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
            final float[] quad = new float[23];
            for (int i = 0; i < 4; i++) {
                final float[] p = rotate(corners[i], cubePivot, cubeRot);
                quad[i * 5] = p[0];
                quad[i * 5 + 1] = p[1];
                quad[i * 5 + 2] = p[2];
                quad[i * 5 + 3] = tex[i][0];
                quad[i * 5 + 4] = tex[i][1];
            }
            final float[] n = rotate(normal, new float[3], cubeRot);
            quad[20] = n[0];
            quad[21] = n[1];
            quad[22] = n[2];
            bone.quads.add(quad);
        }
    }

    /** Face UVs as {u, v, width, height} in texture pixels (negative sizes flip). */
    private static Map<String, float[]> faceUvs(JsonElement uvJson, float[] size, boolean mirror) {
        final Map<String, float[]> out = new HashMap<>();
        if (uvJson == null) {
            return out;
        }
        if (uvJson.isJsonArray()) {
            // Box UV, laid out like a vanilla ModelBox.
            final float u = uvJson.getAsJsonArray().get(0).getAsFloat();
            final float v = uvJson.getAsJsonArray().get(1).getAsFloat();
            final float w = (float) Math.floor(size[0]);
            final float h = (float) Math.floor(size[1]);
            final float d = (float) Math.floor(size[2]);
            out.put("east", new float[]{u, v + d, d, h});
            out.put("north", new float[]{u + d, v + d, w, h});
            out.put("west", new float[]{u + d + w, v + d, d, h});
            out.put("south", new float[]{u + d + w + d, v + d, w, h});
            out.put("up", new float[]{u + d, v, w, d});
            out.put("down", new float[]{u + d + w, v + d, w, -d});
            if (mirror) {
                for (float[] f : out.values()) {
                    f[0] += f[2];
                    f[2] = -f[2];
                }
                final float[] east = out.get("east");
                out.put("east", out.get("west"));
                out.put("west", east);
            }
            return out;
        }
        final JsonObject faces = uvJson.getAsJsonObject();
        for (String face : FACES) {
            final JsonElement f = faces.get(face);
            if (f == null || !f.isJsonObject()) {
                continue;
            }
            final JsonObject o = f.getAsJsonObject();
            final float[] uv = vec(o.get("uv"), 0);
            final float[] uvSize = vec(o.get("uv_size"), 0);
            out.put(face, new float[]{uv[0], uv[1], uvSize[0], uvSize[1]});
        }
        return out;
    }

    /** Corners top-left, top-right, bottom-right, bottom-left as seen from outside the face. */
    private static float[][] corners(String face, float x1, float x2, float y1, float y2, float z1, float z2) {
        switch (face) {
            case "north":
                return new float[][]{{x2, y2, z1}, {x1, y2, z1}, {x1, y1, z1}, {x2, y1, z1}};
            case "south":
                return new float[][]{{x1, y2, z2}, {x2, y2, z2}, {x2, y1, z2}, {x1, y1, z2}};
            case "east":
                return new float[][]{{x2, y2, z2}, {x2, y2, z1}, {x2, y1, z1}, {x2, y1, z2}};
            case "west":
                return new float[][]{{x1, y2, z1}, {x1, y2, z2}, {x1, y1, z2}, {x1, y1, z1}};
            case "up":
                return new float[][]{{x1, y2, z1}, {x2, y2, z1}, {x2, y2, z2}, {x1, y2, z2}};
            default:
                return new float[][]{{x1, y1, z2}, {x2, y1, z2}, {x2, y1, z1}, {x1, y1, z1}};
        }
    }

    private static float[] normal(String face) {
        switch (face) {
            case "north":
                return new float[]{0, 0, -1};
            case "south":
                return new float[]{0, 0, 1};
            case "east":
                return new float[]{1, 0, 0};
            case "west":
                return new float[]{-1, 0, 0};
            case "up":
                return new float[]{0, 1, 0};
            default:
                return new float[]{0, -1, 0};
        }
    }

    /** Rotates {@code p} about {@code pivot}: X, then Y, then Z (GeckoLib signs: X and Y negated). */
    private static float[] rotate(float[] p, float[] pivot, float[] rotDeg) {
        double x = p[0] - pivot[0];
        double y = p[1] - pivot[1];
        double z = p[2] - pivot[2];
        if (rotDeg[0] != 0) {
            final double a = Math.toRadians(-rotDeg[0]);
            final double ny = y * Math.cos(a) - z * Math.sin(a);
            final double nz = y * Math.sin(a) + z * Math.cos(a);
            y = ny;
            z = nz;
        }
        if (rotDeg[1] != 0) {
            final double a = Math.toRadians(-rotDeg[1]);
            final double nx = x * Math.cos(a) + z * Math.sin(a);
            final double nz = -x * Math.sin(a) + z * Math.cos(a);
            x = nx;
            z = nz;
        }
        if (rotDeg[2] != 0) {
            final double a = Math.toRadians(rotDeg[2]);
            final double nx = x * Math.cos(a) - y * Math.sin(a);
            final double ny = x * Math.sin(a) + y * Math.cos(a);
            x = nx;
            y = ny;
        }
        return new float[]{(float) (x + pivot[0]), (float) (y + pivot[1]), (float) (z + pivot[2])};
    }

    private static float[] vec(JsonElement e, float def) {
        final float[] out = {def, def, def};
        if (e != null && e.isJsonArray()) {
            final JsonArray a = e.getAsJsonArray();
            for (int i = 0; i < Math.min(3, a.size()); i++) {
                out[i] = a.get(i).getAsFloat();
            }
        }
        return out;
    }

}

package com.ootruffle.clientmetics.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * A Wavefront OBJ (Lunar's classic hats and bodywear): positions, texture coordinates and normals,
 * with polygons fanned into triangles. Materials are ignored; each cosmetic has one texture.
 * Parsed off-thread, baked into a display list on first draw.
 */
public final class ObjModel {

    /** Triangles as 3 x (x, y, z, u, v, nx, ny, nz). */
    private final float[] vertices;
    private int displayList = -1;

    private ObjModel(float[] vertices) {
        this.vertices = vertices;
    }

    public static ObjModel parse(byte[] data) {
        final List<float[]> positions = new ArrayList<>();
        final List<float[]> uvs = new ArrayList<>();
        final List<float[]> normals = new ArrayList<>();
        final List<Float> out = new ArrayList<>();
        for (String raw : new String(data, StandardCharsets.UTF_8).split("\n")) {
            final String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            final String[] p = line.split("\\s+");
            switch (p[0]) {
                case "v":
                    positions.add(new float[]{f(p, 1), f(p, 2), f(p, 3)});
                    break;
                case "vt":
                    uvs.add(new float[]{f(p, 1), f(p, 2)});
                    break;
                case "vn":
                    normals.add(new float[]{f(p, 1), f(p, 2), f(p, 3)});
                    break;
                case "f": {
                    final int n = p.length - 1;
                    for (int i = 1; i < n - 1; i++) {
                        corner(out, p[1], positions, uvs, normals);
                        corner(out, p[i + 1], positions, uvs, normals);
                        corner(out, p[i + 2], positions, uvs, normals);
                    }
                    break;
                }
                default:
                    break;
            }
        }
        final float[] verts = new float[out.size()];
        for (int i = 0; i < verts.length; i++) {
            verts[i] = out.get(i);
        }
        return new ObjModel(verts);
    }

    private static void corner(List<Float> out, String ref, List<float[]> pos, List<float[]> uv, List<float[]> nrm) {
        final String[] idx = ref.split("/", -1);
        final float[] p = pick(pos, idx[0], new float[3]);
        final float[] t = idx.length > 1 && !idx[1].isEmpty() ? pick(uv, idx[1], new float[2]) : new float[2];
        final float[] n = idx.length > 2 && !idx[2].isEmpty() ? pick(nrm, idx[2], new float[]{0, 1, 0}) : new float[]{0, 1, 0};
        out.add(p[0]);
        out.add(p[1]);
        out.add(p[2]);
        out.add(t[0]);
        out.add(1 - t[1]); // OBJ's V runs bottom to top.
        out.add(n[0]);
        out.add(n[1]);
        out.add(n[2]);
    }

    private static float[] pick(List<float[]> list, String index, float[] def) {
        try {
            int i = Integer.parseInt(index);
            i = i < 0 ? list.size() + i : i - 1;
            return i >= 0 && i < list.size() ? list.get(i) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static float f(String[] p, int i) {
        try {
            return i < p.length ? Float.parseFloat(p[i]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Draws the model with whatever texture is bound (render thread). */
    public void render() {
        if (displayList < 0) {
            displayList = GLAllocation.generateDisplayLists(1);
            GL11.glNewList(displayList, GL11.GL_COMPILE);
            final Tessellator tess = Tessellator.getInstance();
            final WorldRenderer wr = tess.getWorldRenderer();
            wr.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_TEX_NORMAL);
            for (int i = 0; i + 7 < vertices.length; i += 8) {
                wr.pos(vertices[i], vertices[i + 1], vertices[i + 2])
                        .tex(vertices[i + 3], vertices[i + 4])
                        .normal(vertices[i + 5], vertices[i + 6], vertices[i + 7])
                        .endVertex();
            }
            tess.draw();
            GL11.glEndList();
        }
        GlStateManager.callList(displayList);
    }

    /** Frees the display list (render thread). */
    public void delete() {
        if (displayList >= 0) {
            GLAllocation.deleteDisplayLists(displayList);
            displayList = -1;
        }
    }

}

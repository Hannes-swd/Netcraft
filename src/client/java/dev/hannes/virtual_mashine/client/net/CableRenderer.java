package dev.hannes.virtual_mashine.client.net;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.network.NetPayloads;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** Draws every patch cable as a sagging, lit tube between its two sockets (over the clips it hangs from). */
public final class CableRenderer {
    private static final ResourceLocation TEXTURE = Virtual_mashine.id("textures/entity/cable.png");
    private static final float RADIUS = 0.028F;
    /** Faces around the tube: enough to look round up close. */
    private static final int SIDES = 8;
    private static final double MAX_DISTANCE = 128;

    private static volatile List<NetPayloads.CableVisual> cables = List.of();
    private static volatile NetPayloads.HeldCable held = NetPayloads.HeldCable.NONE;

    private CableRenderer() {
    }

    public static void setCables(List<NetPayloads.CableVisual> newCables) {
        cables = List.copyOf(newCables);
    }

    public static void setHeld(NetPayloads.HeldCable newHeld) {
        held = newHeld;
    }

    public static void clear() {
        cables = List.of();
        held = NetPayloads.HeldCable.NONE;
    }

    public static void render(WorldRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        List<NetPayloads.CableVisual> all = cables;
        NetPayloads.HeldCable inHand = held;
        if (level == null || (all.isEmpty() && !inHand.active())) {
            return;
        }
        Vec3 camera = context.camera().getPosition();
        MultiBufferSource buffers = context.consumers();
        if (buffers == null) {
            return;
        }
        PoseStack.Pose pose = (context.matrixStack() != null ? context.matrixStack() : new PoseStack()).last();
        VertexConsumer buffer = buffers.getBuffer(RenderType.entitySolid(TEXTURE));
        for (NetPayloads.CableVisual cable : all) {
            if (cable.patch()) {
                patch(buffer, pose, level, camera, cable.points(), cable.color());
                continue;
            }
            // From socket to socket over every clip it hangs from; each stretch sags on its own.
            List<Vec3> points = cable.points();
            for (int i = 1; i < points.size(); i++) {
                Vec3 a = points.get(i - 1);
                Vec3 b = points.get(i);
                if (a.distanceTo(camera) <= MAX_DISTANCE || b.distanceTo(camera) <= MAX_DISTANCE) {
                    tube(buffer, pose, level, camera, a, b, cable.color());
                }
            }
        }
        LocalPlayer player = minecraft.player;
        if (inHand.active() && player != null && !inHand.points().isEmpty()) {
            float partial = minecraft.getTimer().getGameTimeDeltaPartialTick(true);
            Vec3 eye = player.getEyePosition(partial);
            Vec3 look = player.getViewVector(partial);
            Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize();
            Vec3 hand = eye.add(look.scale(0.45)).add(right.scale(0.28)).add(0, -0.38, 0);
            List<Vec3> points = inHand.points();
            for (int i = 1; i < points.size(); i++) {
                tube(buffer, pose, level, camera, points.get(i - 1), points.get(i), inHand.color());
            }
            tube(buffer, pose, level, camera, points.get(points.size() - 1), hand, inHand.color());
        }
    }

    private static void tube(VertexConsumer buffer, PoseStack.Pose pose, ClientLevel level, Vec3 camera, Vec3 a, Vec3 b, int color) {
        double length = a.distanceTo(b);
        if (length < 1.0E-3) {
            return;
        }
        double horizontal = Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z));
        double sag = Math.min(1.6, 0.05 + horizontal * 0.06);
        int segments = (int) Math.max(8, Math.min(48, length * 2));
        List<Vec3> points = new ArrayList<>(segments + 1);
        for (int i = 0; i <= segments; i++) {
            points.add(point(a, b, sag, (double) i / segments));
        }
        strand(buffer, pose, level, camera, points, 0xFF000000 | color);
    }

    /** A patch cable inside a rack: a smooth curve through four control points, without sagging. */
    private static void patch(VertexConsumer buffer, PoseStack.Pose pose, ClientLevel level, Vec3 camera, List<Vec3> controls, int color) {
        if (controls.size() != 4 || controls.get(0).distanceTo(camera) > MAX_DISTANCE) {
            return;
        }
        List<Vec3> points = new ArrayList<>(25);
        for (int i = 0; i <= 24; i++) {
            points.add(bezier(controls, i / 24.0));
        }
        strand(buffer, pose, level, camera, points, 0xFF000000 | color);
    }

    private static Vec3 bezier(List<Vec3> p, double t) {
        double u = 1 - t;
        return p.get(0).scale(u * u * u).add(p.get(1).scale(3 * u * u * t)).add(p.get(2).scale(3 * u * t * t)).add(p.get(3).scale(t * t * t));
    }

    /** Position along a cable hanging between a and b. */
    private static Vec3 point(Vec3 a, Vec3 b, double sag, double t) {
        return new Vec3(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t - sag * 4 * t * (1 - t),
                a.z + (b.z - a.z) * t);
    }

    /**
     * One seamless tube along the points: a ring around every point, its orientation carried along the
     * cable (so it does not twist in bends), neighbouring rings joined by quads.
     */
    private static void strand(VertexConsumer buffer, PoseStack.Pose pose, ClientLevel level, Vec3 camera, List<Vec3> points, int argb) {
        int count = points.size();
        if (count < 2) {
            return;
        }
        Vector3f[] up = new Vector3f[count];
        Vector3f[] side = new Vector3f[count];
        Vector3f carried = null;
        for (int i = 0; i < count; i++) {
            Vec3 before = points.get(Math.max(0, i - 1));
            Vec3 after = points.get(Math.min(count - 1, i + 1));
            Vector3f tangent = new Vector3f((float) (after.x - before.x), (float) (after.y - before.y), (float) (after.z - before.z));
            if (tangent.lengthSquared() < 1.0E-12F) {
                tangent.set(0, 0, 1);
            }
            tangent.normalize();
            if (carried == null) {
                // Start: "up" as close to the world's up as the direction allows.
                carried = Math.abs(tangent.y) > 0.99F ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
            }
            // Parallel transport: keep last ring's up, only remove the part along the new direction.
            Vector3f u = new Vector3f(carried).sub(new Vector3f(tangent).mul(carried.dot(tangent)));
            if (u.lengthSquared() < 1.0E-8F) {
                u = Math.abs(tangent.y) > 0.99F ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
                u.sub(new Vector3f(tangent).mul(u.dot(tangent)));
            }
            u.normalize();
            up[i] = u;
            side[i] = new Vector3f(tangent).cross(u).normalize();
            carried = u;
        }
        int[] light = new int[count];
        for (int i = 0; i < count; i++) {
            light[i] = LevelRenderer.getLightColor(level, BlockPos.containing(points.get(i)));
        }
        for (int i = 0; i + 1 < count; i++) {
            Vec3 from = points.get(i);
            Vec3 to = points.get(i + 1);
            for (int j = 0; j < SIDES; j++) {
                // Counter-clockwise seen from outside, so back-face culling keeps the outer side.
                double lo = 2 * Math.PI * (j - 0.5) / SIDES;
                double hi = 2 * Math.PI * (j + 0.5) / SIDES;
                ringVertex(buffer, pose, camera, from, up[i], side[i], hi, argb, 0, light[i]);
                ringVertex(buffer, pose, camera, to, up[i + 1], side[i + 1], hi, argb, 1, light[i + 1]);
                ringVertex(buffer, pose, camera, to, up[i + 1], side[i + 1], lo, argb, 1, light[i + 1]);
                ringVertex(buffer, pose, camera, from, up[i], side[i], lo, argb, 0, light[i]);
            }
        }
    }

    private static void ringVertex(VertexConsumer buffer, PoseStack.Pose pose, Vec3 camera, Vec3 centre, Vector3f up, Vector3f side,
                                   double angle, int argb, float u, int light) {
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        Vector3f normal = new Vector3f(up).mul(cos).add(new Vector3f(side).mul(sin));
        vertex(buffer, pose,
                (float) (centre.x - camera.x) + normal.x * RADIUS,
                (float) (centre.y - camera.y) + normal.y * RADIUS,
                (float) (centre.z - camera.z) + normal.z * RADIUS,
                argb, u, (float) (angle / (2 * Math.PI)), light, normal);
    }


    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, int argb, float u, float v, int light, Vector3f normal) {
        buffer.addVertex(pose, x, y, z)
                .setColor(argb)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, normal.x, normal.y, normal.z);
    }
}

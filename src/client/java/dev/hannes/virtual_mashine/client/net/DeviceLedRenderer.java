package dev.hannes.virtual_mashine.client.net;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.block.DeviceBlock;
import dev.hannes.virtual_mashine.block.DeviceBlockEntity;
import dev.hannes.virtual_mashine.client.display.DisplayManager;
import dev.hannes.virtual_mashine.client.display.ScreenRenderType;
import dev.hannes.virtual_mashine.device.DeviceType;
import dev.hannes.virtual_mashine.net.DeviceGeometry;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** The little link (green) and traffic (amber) LEDs above every socket on the device's housing. */
public final class DeviceLedRenderer implements BlockEntityRenderer<DeviceBlockEntity> {
    private static final ResourceLocation TEXTURE = Virtual_mashine.id("textures/entity/cable.png");
    private static final float SIZE = 0.022F;

    public DeviceLedRenderer(BlockEntityRendererProvider.Context context) {
    }

    /** A large display reaches past its own block: keep drawing it while only part of it is in view. */
    @Override
    public boolean shouldRenderOffScreen(DeviceBlockEntity device) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 96;
    }

    @Override
    public void render(DeviceBlockEntity device, float partialTicks, PoseStack poseStack, MultiBufferSource buffers,
                       int packedLight, int packedOverlay) {
        BlockState blockState = device.getBlockState();
        if (!(blockState.getBlock() instanceof DeviceBlock block)) {
            return;
        }
        DeviceType type = block.deviceType();
        Direction facing = blockState.getValue(DeviceBlock.FACING);
        int link = device.linkLeds();
        int activity = device.activityLeds();
        if (type == DeviceType.DISPLAY && device.getPcId() != null) {
            ResourceLocation screen = DisplayManager.frame(device.getPcId(), device.getBlockPos(), facing, device.displayWidth(),
                    device.displayHeight(), device.displayPort(), device.displaySource(), device.displayHead());
            if (screen != null) {
                renderScreen(facing, device.displayWidth(), device.displayHeight(), screen, poseStack, buffers);
            }
        }
        renderLeds(type, facing, link, activity, poseStack, buffers);
    }

    /** The port LEDs of one device, in block coordinates of a device standing at the origin. */
    public static void renderLeds(DeviceType type, Direction facing, int link, int activity, PoseStack poseStack, MultiBufferSource buffers) {
        if (link == 0) {
            return;
        }
        boolean blinkOn = (System.currentTimeMillis() / 90) % 2 == 0;
        VertexConsumer buffer = buffers.getBuffer(RenderType.entitySolid(TEXTURE));
        PoseStack.Pose pose = poseStack.last();
        for (int port = 0; port < Math.min(32, type.totalPorts()); port++) {
            if ((link & (1 << port)) == 0 || type.isUsbPort(port) || type.isHdmiPort(port)) {
                continue; // USB and HDMI sockets have no LEDs.
            }
            Vec3 socket = DeviceGeometry.socket(type, net.minecraft.core.BlockPos.ZERO, facing, port);
            // Same faces as DeviceGeometry: switches/routers have network ports in front, everything else at the back.
            Direction face = !type.isConsolePort(port) && type.portsOnFront ? facing : facing.getOpposite();
            Direction left = face.getClockWise();
            double y = socket.y + 0.055;
            // Link LED on the left, traffic LED on the right of the socket.
            Vec3 linkLed = socket.add(left.getStepX() * 0.03, y - socket.y, left.getStepZ() * 0.03);
            Vec3 trafficLed = socket.add(-left.getStepX() * 0.03, y - socket.y, -left.getStepZ() * 0.03);
            square(buffer, pose, linkLed, face, left, 0xFF3DFF5A);
            if ((activity & (1 << port)) != 0 && blinkOn) {
                square(buffer, pose, trafficLed, face, left, 0xFFFFB12E);
            }
        }
    }

    /** The display's picture: a lit rectangle on its front, growing to the right and upwards. */
    private static void renderScreen(Direction facing, int width, int height, ResourceLocation screen, PoseStack poseStack,
                                     MultiBufferSource buffers) {
        Direction right = facing.getCounterClockWise();
        float bx = 0.5F + facing.getStepX() * 0.503F - right.getStepX() * 0.5F;
        float bz = 0.5F + facing.getStepZ() * 0.503F - right.getStepZ() * 0.5F;
        float rx = right.getStepX() * width;
        float rz = right.getStepZ() * width;
        float h = height;
        VertexConsumer buffer = buffers.getBuffer(ScreenRenderType.of(screen));
        PoseStack.Pose pose = poseStack.last();
        // Counter-clockwise as seen from the front: bottom left, bottom right, top right, top left.
        screenVertex(buffer, pose, bx, 0, bz, 0, 1);
        screenVertex(buffer, pose, bx + rx, 0, bz + rz, 1, 1);
        screenVertex(buffer, pose, bx + rx, h, bz + rz, 1, 0);
        screenVertex(buffer, pose, bx, h, bz, 0, 0);
    }


    private static void screenVertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, float u, float v) {
        buffer.addVertex(pose, x, y, z).setUv(u, v);
    }

    /** A tiny glowing square on the face, facing outwards. */
    private static void square(VertexConsumer buffer, PoseStack.Pose pose, Vec3 centre, Direction face, Direction left, int argb) {
        float cx = (float) (centre.x + face.getStepX() * 0.004);
        float cy = (float) centre.y;
        float cz = (float) (centre.z + face.getStepZ() * 0.004);
        float lx = left.getStepX() * SIZE / 2;
        float lz = left.getStepZ() * SIZE / 2;
        float h = SIZE / 3;
        float nx = face.getStepX();
        float nz = face.getStepZ();
        // Counter-clockwise seen from outside the face.
        vertex(buffer, pose, cx + lx, cy - h, cz + lz, argb, nx, nz);
        vertex(buffer, pose, cx - lx, cy - h, cz - lz, argb, nx, nz);
        vertex(buffer, pose, cx - lx, cy + h, cz - lz, argb, nx, nz);
        vertex(buffer, pose, cx + lx, cy + h, cz + lz, argb, nx, nz);
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, int argb, float nx, float nz) {
        buffer.addVertex(pose, x, y, z)
                .setColor(argb)
                .setUv(0.5F, 0.5F)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, nx, 0, nz);
    }
}

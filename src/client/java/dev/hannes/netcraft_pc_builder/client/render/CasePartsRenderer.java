package dev.hannes.netcraft_pc_builder.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.hannes.netcraft_pc_builder.block.CaseBlock;
import dev.hannes.virtual_mashine.block.DeviceBlock;
import dev.hannes.netcraft_pc_builder.pc.CaseSize;
import dev.hannes.netcraft_pc_builder.pc.PcBuild;
import dev.hannes.netcraft_pc_builder.pc.PcPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The parts inside a case, seen through its glass side panel: small boxes for the mainboard, CPU,
 * cooler, RAM, graphics card, power supply and drives, placed and sized like in the build.
 * Coordinates are pixels of a case facing north (front = north, window = east), turned with the block.
 */
public final class CasePartsRenderer {
    private static final BlockState BOARD = Blocks.GREEN_CONCRETE.defaultBlockState();
    private static final BlockState METAL = Blocks.IRON_BLOCK.defaultBlockState();
    private static final BlockState DARK = Blocks.BLACK_CONCRETE.defaultBlockState();
    private static final BlockState GRAY = Blocks.GRAY_CONCRETE.defaultBlockState();
    private static final BlockState LIGHT = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
    private static final BlockState RAM = Blocks.PURPLE_CONCRETE.defaultBlockState();
    private static final BlockState SSD = Blocks.BLUE_CONCRETE.defaultBlockState();
    private static final BlockState GOLD = Blocks.GOLD_BLOCK.defaultBlockState();

    private CasePartsRenderer() {
    }

    /** Inset of the case box from the block edges and its height: {x inset, height, z inset} in pixels. */
    public static int[] bounds(CaseSize size) {
        return switch (size) {
            case SMALL -> new int[]{4, 11, 2};
            case MEDIUM -> new int[]{3, 15, 1};
            case LARGE -> new int[]{2, 16, 0};
        };
    }

    public static void render(CaseBlock block, BlockState state, PcBuild build, PoseStack poseStack, MultiBufferSource buffers,
                              int light, int overlay) {
        Direction facing = state.getValue(DeviceBlock.FACING);
        float yRot = switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
        poseStack.pushPose();
        poseStack.translate(0.5F, 0, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-yRot));
        poseStack.translate(-0.5F, 0, -0.5F);
        new Drawer(block.size(), build, poseStack, buffers, light, overlay).draw();
        poseStack.popPose();
    }

    private static final class Drawer {
        private final PcBuild build;
        private final PoseStack poseStack;
        private final MultiBufferSource buffers;
        private final int light;
        private final int overlay;
        private final BlockRenderDispatcher blocks = Minecraft.getInstance().getBlockRenderer();
        // Inside of the case.
        private final float wall;   // x of the inner side of the mainboard wall
        private final float window; // x of the glass
        private final float front;  // z of the inner front
        private final float back;   // z of the inner back
        private final float floor;
        private final float roof;
        private final float width;  // window - wall
        private final float depth;  // back - front
        private final float height; // roof - floor

        Drawer(CaseSize size, PcBuild build, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
            this.build = build;
            this.poseStack = poseStack;
            this.buffers = buffers;
            this.light = light;
            this.overlay = overlay;
            int[] b = bounds(size);
            wall = b[0] + 1;
            window = 16 - b[0] - 0.3F;
            front = b[2] + 1;
            back = 16 - b[2] - 1;
            floor = 1;
            roof = b[1] - 1;
            width = window - wall;
            depth = back - front;
            height = roof - floor;
        }

        private void box(float x1, float y1, float z1, float x2, float y2, float z2, BlockState state) {
            float ax = Math.min(x1, x2), bx = Math.max(x1, x2);
            float ay = Math.min(y1, y2), by = Math.max(y1, y2);
            float az = Math.min(z1, z2), bz = Math.max(z1, z2);
            if (bx - ax < 0.01F || by - ay < 0.01F || bz - az < 0.01F) return;
            poseStack.pushPose();
            poseStack.translate(ax / 16F, ay / 16F, az / 16F);
            poseStack.scale((bx - ax) / 16F, (by - ay) / 16F, (bz - az) / 16F);
            blocks.renderSingleBlock(state, poseStack, buffers, light, overlay);
            poseStack.popPose();
        }

        // Board-local position: u from the back edge towards the front, v from the top edge down.
        private float boardZ0;
        private float boardLen;
        private float boardTop;
        private float boardHeight;
        private float boardFace;

        private float z(double u) {
            return (float) (boardZ0 - u * boardLen);
        }

        private float y(double v) {
            return (float) (boardTop - v * boardHeight);
        }

        void draw() {
            drawPsu();
            drawDrives();
            PcPart board = build.get("mainboard");
            if (board == null) return;
            double[] scale = switch (board.str("form")) {
                case "ATX" -> new double[]{0.82, 0.74};
                case "mATX" -> new double[]{0.68, 0.64};
                default -> new double[]{0.52, 0.52};
            };
            boardZ0 = back - 0.4F;
            boardLen = (float) (depth * scale[0]);
            boardTop = roof - 0.5F;
            boardHeight = (float) (height * scale[1]);
            boardFace = wall + 0.35F;
            box(wall, y(0), z(0), boardFace, y(1), z(1), BOARD);

            PcPart cpu = build.get("cpu");
            if (cpu != null) {
                box(boardFace, y(0.16), z(0.40), boardFace + 0.3F, y(0.34), z(0.60), METAL);
            }
            PcPart cooler = build.get("cooler");
            if (cooler != null && "aio".equals(cooler.str("type"))) {
                box(boardFace, y(0.18), z(0.42), boardFace + 1.2F, y(0.32), z(0.58), GRAY);
                float radLen = Math.min(depth - 0.5F, depth * cooler.num("radiator_mm") / 380F);
                box(window - 0.2F - Math.min(width * 0.45F, 2.6F), roof - 1.3F, back - 0.2F, window - 0.2F, roof, back - 0.2F - radLen, DARK);
            } else if (cooler != null) {
                float out = Math.min(width - 0.6F, width * cooler.num("height_mm") / 175F);
                box(boardFace + 0.3F, y(0.08), z(0.33), boardFace + out, y(0.42), z(0.67), METAL);
                box(boardFace + 0.6F, y(0.10), z(0.67), boardFace + out - 0.3F, y(0.40), z(0.69), DARK);
            } else if (cpu != null && cpu.bool("box_cooler")) {
                box(boardFace + 0.3F, y(0.14), z(0.37), boardFace + 1.1F, y(0.36), z(0.63), DARK);
            }

            int stick = 0;
            for (int kit = 0; kit < 2; kit++) {
                if (build.get("ram" + kit) == null) {
                    stick += 2;
                    continue;
                }
                for (int i = 0; i < 2; i++, stick++) {
                    double u = 0.74 + stick * 0.055;
                    box(boardFace, y(0.05), z(u), boardFace + 1.5F, y(0.44), z(u + 0.025), RAM);
                    box(boardFace + 1.5F, y(0.05), z(u), boardFace + 1.6F, y(0.44), z(u + 0.025), GOLD);
                }
            }

            for (int i = 0; i < 4; i++) {
                if (build.get("m2_" + i) != null) {
                    box(boardFace, y(0.48 + i * 0.06), z(0.30), boardFace + 0.15F, y(0.515 + i * 0.06), z(0.68), SSD);
                }
            }

            PcPart gpu = build.get("gpu");
            if (gpu != null) {
                float len = Math.min(depth - 0.3F, depth * gpu.num("length_mm") / 400F);
                float thick = Math.max(1.0F, height * 0.09F);
                float top = y(0.66);
                float out = Math.min(width - 0.5F, width * 0.42F);
                box(boardFace, top, boardZ0, boardFace + out, top - thick, boardZ0 - len, GRAY);
                box(boardFace + 0.2F, top - thick, boardZ0 - 0.3F, boardFace + out - 0.2F, top - thick - 0.15F, boardZ0 - len + 0.3F, DARK);
            }
        }

        private void drawPsu() {
            PcPart psu = build.get("psu");
            if (psu == null) return;
            float h = Math.max(1.5F, height * 0.2F);
            box(wall + 0.6F, floor, back, window - 0.4F, floor + h, back - depth * 0.42F, DARK);
            box(window - 0.45F, floor + 0.3F, back - 0.4F, window - 0.4F, floor + h - 0.3F, back - depth * 0.42F + 0.4F, GRAY);
        }

        private void drawDrives() {
            float y = floor;
            for (String slot : build.slots()) {
                if (!slot.startsWith("bay") || build.get(slot) == null) continue;
                boolean big = slot.startsWith("bay35_");
                float h = big ? 1.1F : 0.6F;
                box(window - 0.5F - width * (big ? 0.55F : 0.45F), y, front + 0.2F, window - 0.5F, y + h, front + 0.2F + depth * (big ? 0.32F : 0.25F),
                        big ? LIGHT : SSD);
                y += h + 0.3F;
            }
        }
    }
}

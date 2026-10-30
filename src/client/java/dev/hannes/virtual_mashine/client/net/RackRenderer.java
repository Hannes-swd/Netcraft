package dev.hannes.virtual_mashine.client.net;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.hannes.virtual_mashine.ModRegistry;
import dev.hannes.virtual_mashine.block.DeviceBlock;
import dev.hannes.virtual_mashine.block.RackBlock;
import dev.hannes.virtual_mashine.block.RackBlockEntity;
import dev.hannes.virtual_mashine.net.DeviceGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/** The devices mounted in a rack: each drawn with its own model at its height, a little narrower, with its LEDs. */
public final class RackRenderer implements BlockEntityRenderer<RackBlockEntity> {
    public RackRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(RackBlockEntity rack, float partialTicks, PoseStack poseStack, MultiBufferSource buffers,
                       int packedLight, int packedOverlay) {
        Direction facing = rack.getBlockState().getValue(RackBlock.FACING);
        float scale = (float) DeviceGeometry.RACK_SCALE;
        for (RackBlockEntity.Slot slot : rack.slots()) {
            BlockState device = ModRegistry.DEVICE_BLOCKS.get(slot.type).defaultBlockState()
                    .setValue(DeviceBlock.FACING, facing)
                    .setValue(DeviceBlock.LIT, slot.lit);
            poseStack.pushPose();
            poseStack.translate(0.5F, (float) slot.rackY(), 0.5F);
            poseStack.scale(scale, 1.0F, scale);
            poseStack.translate(-0.5F, 0.0F, -0.5F);
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(device, poseStack, buffers, packedLight, packedOverlay);
            DeviceLedRenderer.renderLeds(slot.type, facing, slot.link, slot.activity, poseStack, buffers);
            poseStack.popPose();
        }
    }
}

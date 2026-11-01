package dev.hannes.virtual_mashine.block;

import dev.hannes.virtual_mashine.ModRegistry;
import dev.hannes.virtual_mashine.item.PcPartItem;
import dev.hannes.virtual_mashine.network.CasePayload;
import dev.hannes.virtual_mashine.pc.CaseSize;
import dev.hannes.virtual_mashine.pc.PcBuild;
import dev.hannes.virtual_mashine.pc.PcPart;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/** The parts built into a case: slot key to part id, synced to clients for the build screen. */
public class CaseBlockEntity extends BlockEntity {
    private final Map<String, String> parts = new LinkedHashMap<>();

    public CaseBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.CASE_BLOCK_ENTITY, pos, state);
    }

    public CaseSize size() {
        return getBlockState().getBlock() instanceof CaseBlock block ? block.size() : CaseSize.MEDIUM;
    }

    public PcBuild build() {
        return new PcBuild(size(), parts);
    }

    public void dropAll() {
        if (level == null) return;
        PcBuild build = build();
        for (String slot : parts.keySet()) {
            PcPart part = build.get(slot);
            if (part != null) Block.popResource(level, worldPosition, PcPartItem.stackOf(part));
        }
        parts.clear();
    }

    private void changed() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    private static void deny(ServerPlayer player, String reason) {
        player.displayClientMessage(Component.literal(reason).withStyle(ChatFormatting.RED), true);
    }

    public static void handle(ServerPlayer player, CasePayload payload) {
        if (player.distanceToSqr(Vec3.atCenterOf(payload.pos())) > 64
                || !(player.level().getBlockEntity(payload.pos()) instanceof CaseBlockEntity entity)) {
            return;
        }
        PcBuild build = entity.build();
        if (payload.install()) {
            Inventory inventory = player.getInventory();
            if (payload.invSlot() < 0 || payload.invSlot() >= inventory.getContainerSize()) return;
            ItemStack stack = inventory.getItem(payload.invSlot());
            PcPart part = PcPartItem.part(stack);
            if (part == null) return;
            String reason = build.canInstall(payload.slot(), part);
            if (reason != null) {
                deny(player, reason);
                return;
            }
            entity.parts.put(payload.slot(), part.id());
            if (!player.getAbilities().instabuild) stack.shrink(1);
            entity.changed();
            player.level().playSound(null, payload.pos(), SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.4F, 1.8F);
        } else {
            PcPart part = build.get(payload.slot());
            if (part == null) return;
            String reason = build.canRemove(payload.slot());
            if (reason != null) {
                deny(player, reason);
                return;
            }
            entity.parts.remove(payload.slot());
            entity.changed();
            ItemStack stack = PcPartItem.stackOf(part);
            if (!player.getInventory().add(stack)) player.drop(stack, false);
            player.level().playSound(null, payload.pos(), SoundEvents.IRON_TRAPDOOR_OPEN, SoundSource.BLOCKS, 0.4F, 1.8F);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        parts.clear();
        CompoundTag saved = tag.getCompound("parts");
        for (String slot : saved.getAllKeys()) {
            parts.put(slot, saved.getString(slot));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        CompoundTag saved = new CompoundTag();
        parts.forEach(saved::putString);
        tag.put("parts", saved);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }
}

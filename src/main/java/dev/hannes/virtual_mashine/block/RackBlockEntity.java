package dev.hannes.virtual_mashine.block;

import dev.hannes.virtual_mashine.ModRegistry;
import dev.hannes.virtual_mashine.device.DeviceType;
import dev.hannes.virtual_mashine.vm.VmManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The devices mounted in a rack, each with its own machine id, kind and height. They run and are cabled
 * exactly like devices standing on their own; their block is the rack.
 */
public class RackBlockEntity extends BlockEntity {
    /** Inner height of a rack in pixels. */
    public static final int HEIGHT = 16;

    /** One mounted device: {@code bottom} in pixels above the rack's floor. */
    public static final class Slot {
        public final UUID id;
        public final DeviceType type;
        public final int bottom;
        /** Synced to clients for drawing: power LED and the port LEDs as in {@link DeviceBlockEntity}. */
        public boolean lit;
        public int link;
        public int activity;

        Slot(UUID id, DeviceType type, int bottom) {
            this.id = id;
            this.type = type;
            this.bottom = bottom;
        }

        public int top() {
            return bottom + type.rackHeight();
        }

        /** Height of the device's bottom in blocks, as the network keeps it. */
        public double rackY() {
            return bottom / 16.0;
        }
    }

    private final List<Slot> slots = new ArrayList<>();

    public RackBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.RACK_BLOCK_ENTITY, pos, state);
    }

    public List<Slot> slots() {
        return Collections.unmodifiableList(slots);
    }

    /** The device at this height inside the rack (pixels from its floor), or null. */
    public @Nullable Slot slotAt(double yPixels) {
        for (Slot slot : slots) {
            if (yPixels >= slot.bottom - 0.01 && yPixels <= slot.top() + 0.01) {
                return slot;
            }
        }
        return null;
    }

    /**
     * Where a device of this height goes: devices stack from the top down, a gap left by one taken
     * out is filled again. -1 = no room.
     */
    public int freeBottom(int height) {
        List<Integer> tops = new ArrayList<>();
        tops.add(HEIGHT);
        for (Slot slot : slots) {
            tops.add(slot.bottom);
        }
        tops.sort(Collections.reverseOrder());
        for (int top : tops) {
            int bottom = top - height;
            if (bottom >= 0 && slots.stream().noneMatch(slot -> bottom < slot.top() && top > slot.bottom)) {
                return bottom;
            }
        }
        return -1;
    }

    public Slot mount(UUID id, DeviceType type, int bottom) {
        Slot slot = new Slot(id, type, bottom);
        slots.add(slot);
        changed();
        return slot;
    }

    /** Takes the device out: its cables fall off and its machine is parked, like breaking its block. */
    public void unmount(Slot slot, boolean drop) {
        if (!slots.remove(slot)) {
            return;
        }
        VmManager manager = VmManager.get();
        if (manager != null && level instanceof ServerLevel serverLevel) {
            manager.onPcRemoved(slot.id);
            manager.network().deviceRemoved(slot.id, serverLevel, worldPosition);
        }
        if (drop && level != null) {
            ItemStack stack = new ItemStack(ModRegistry.DEVICE_ITEMS.get(slot.type));
            stack.set(ModRegistry.PC_ID, slot.id); // The machine and its disk travel with the item.
            Block.popResource(level, worldPosition, stack);
        }
        changed();
    }

    /** The rack is gone: every device comes out. */
    public void unmountAll(boolean drop) {
        for (Slot slot : List.copyOf(slots)) {
            unmount(slot, drop);
        }
    }

    private void changed() {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, RackBlockEntity rack) {
        if (level.getGameTime() % 10 != 0 || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        VmManager manager = VmManager.get();
        if (manager == null) {
            return;
        }
        Direction facing = state.getValue(RackBlock.FACING);
        boolean dirty = false;
        for (Slot slot : rack.slots) {
            if (level.getGameTime() % 20 == 0) {
                manager.network().deviceTick(slot.id, slot.type, serverLevel, pos, facing, slot.rackY());
            }
            boolean powered = manager.network().isPowered(slot.id, slot.type);
            long leds = manager.network().ledState(slot.id, slot.type);
            if (powered != slot.lit || (int) leds != slot.link || (int) (leds >>> 32) != slot.activity) {
                slot.lit = powered;
                slot.link = (int) leds;
                slot.activity = (int) (leds >>> 32);
                dirty = true;
            }
        }
        if (dirty) {
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        slots.clear();
        ListTag list = tag.getList("devices", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID("id")) {
                continue;
            }
            Slot slot = new Slot(entry.getUUID("id"), DeviceType.byId(entry.getString("type")), entry.getInt("bottom"));
            slot.lit = entry.getBoolean("lit");
            slot.link = entry.getInt("link");
            slot.activity = entry.getInt("activity");
            slots.add(slot);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag list = new ListTag();
        for (Slot slot : slots) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", slot.id);
            entry.putString("type", slot.type.id);
            entry.putInt("bottom", slot.bottom);
            entry.putBoolean("lit", slot.lit);
            entry.putInt("link", slot.link);
            entry.putInt("activity", slot.activity);
            list.add(entry);
        }
        tag.put("devices", list);
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

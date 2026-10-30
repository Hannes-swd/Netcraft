package dev.hannes.virtual_mashine.block;

import dev.hannes.virtual_mashine.ModRegistry;
import dev.hannes.virtual_mashine.device.DeviceType;
import dev.hannes.virtual_mashine.net.IoModule;
import dev.hannes.virtual_mashine.net.MicMode;
import net.minecraft.core.Direction;
import dev.hannes.virtual_mashine.vm.VmManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Remembers which machine belongs to this device. The id travels with the dropped item (see the
 * block's loot table), so a device that is broken and placed again keeps its disk.
 */
public class DeviceBlockEntity extends BlockEntity {
    private @Nullable UUID pcId;
    /** Per port: bit set = link LED on. Synced to clients for the LEDs drawn on the housing. */
    private int linkLeds;
    /** Per port: bit set = traffic in the last moment (blinking amber LED). */
    private int activityLeds;
    /** I/O module: output levels and output mask as in {@link IoModule#packedOutputs()}, synced for the signals. */
    private int ioOutputs;
    /** Display: size in blocks and the VNC port of its running system (0 = off), synced for drawing. */
    private int displayWidth = 2;
    private int displayHeight = 1;
    private int displayPort;
    /** Display: the computer whose screen arrives over HDMI (null = its own system) and which of its screens. */
    private @Nullable UUID displaySource;
    private int displayHead;
    /** Microphone and camera: the switch on the device. */
    private boolean usbEnabled = true;
    /** Microphone: what it picks up, a {@link MicMode} index. */
    private int micMode;
    /** HDMI switch: the same picture on all outputs instead of a screen of its own each. */
    private boolean hdmiMirrored;

    public DeviceBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.DEVICE_BLOCK_ENTITY, pos, state);
    }

    public UUID getOrCreatePcId() {
        if (pcId == null) {
            pcId = UUID.randomUUID();
            setChanged();
        }
        return pcId;
    }

    public @Nullable UUID getPcId() {
        return pcId;
    }

    public int linkLeds() {
        return linkLeds;
    }

    public int activityLeds() {
        return activityLeds;
    }

    public int ioOutputs() {
        return ioOutputs;
    }

    public int displayWidth() {
        return displayWidth;
    }

    public int displayHeight() {
        return displayHeight;
    }

    public int displayPort() {
        return displayPort;
    }

    public @Nullable UUID displaySource() {
        return displaySource;
    }

    public int displayHead() {
        return displayHead;
    }

    public boolean usbEnabled() {
        return usbEnabled;
    }

    public MicMode micMode() {
        return MicMode.byIndex(micMode);
    }

    public void setMicMode(MicMode mode) {
        micMode = mode.ordinal();
        setChanged();
    }

    public boolean hdmiMirrored() {
        return hdmiMirrored;
    }

    public void setHdmiMirrored(boolean mirrored) {
        hdmiMirrored = mirrored;
        setChanged();
    }

    public void setUsbEnabled(boolean enabled) {
        usbEnabled = enabled;
        setChanged();
    }

    /**
     * Every tick: reads the signal arriving at each input face and drives the output faces with
     * what the network set, so a Modbus write moves a piston in the next tick.
     */
    private static void ioTick(ServerLevel level, BlockPos pos, BlockState state, DeviceBlockEntity device, VmManager manager) {
        IoModule io = manager.network().ioModule(device.getOrCreatePcId());
        Direction facing = state.getValue(DeviceBlock.FACING);
        for (int ch = 0; ch < IoModule.CHANNELS; ch++) {
            Direction side = DeviceBlock.ioSide(facing, ch);
            io.setInput(ch, io.isOutput(ch) ? 0 : level.getSignal(pos.relative(side), side));
        }
        int packed = io.packedOutputs();
        if (packed != device.ioOutputs) {
            int before = device.ioOutputs;
            device.ioOutputs = packed;
            device.setChanged();
            level.updateNeighborsAt(pos, state.getBlock());
            for (int ch = 0; ch < IoModule.CHANNELS; ch++) {
                if (IoModule.packedLevel(before, ch) != IoModule.packedLevel(packed, ch)
                        || IoModule.packedIsOutput(before, ch) != IoModule.packedIsOutput(packed, ch)) {
                    // The block behind an output face is strongly powered: its neighbours must hear it too.
                    level.updateNeighborsAt(pos.relative(DeviceBlock.ioSide(facing, ch)), state.getBlock());
                }
            }
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, DeviceBlockEntity device) {
        if (state.getBlock() instanceof DeviceBlock io && io.deviceType() == DeviceType.IO_MODULE
                && level instanceof ServerLevel serverLevel && VmManager.get() != null) {
            ioTick(serverLevel, pos, state, device, VmManager.get());
        }
        if (level.getGameTime() % 10 != 0 || !(state.getBlock() instanceof DeviceBlock block)
                || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        device.getOrCreatePcId();
        VmManager manager = VmManager.get();
        if (manager == null) {
            return;
        }
        if (level.getGameTime() % 20 == 0) {
            manager.network().deviceTick(device.pcId, block.deviceType(), serverLevel, pos, state.getValue(DeviceBlock.FACING));
            if (block.deviceType().isUsbDevice()) {
                manager.network().setUsbEnabled(device.pcId, device.usbEnabled);
            }
            if (block.deviceType() == DeviceType.MICROPHONE) {
                manager.network().setMicMode(device.pcId, device.micMode());
            }
            if (block.deviceType() == DeviceType.HDMI_SWITCH) {
                manager.network().setHdmiMirrored(device.pcId, device.hdmiMirrored);
            }
        }
        boolean powered = manager.network().isPowered(device.pcId, block.deviceType());
        if (state.getValue(DeviceBlock.LIT) != powered) {
            level.setBlock(pos, state.setValue(DeviceBlock.LIT, powered), Block.UPDATE_ALL);
        }
        if (block.deviceType() == DeviceType.DISPLAY) {
            VmManager.DisplayView view = manager.displayView(device.pcId);
            if (view.width() != device.displayWidth || view.height() != device.displayHeight || view.port() != device.displayPort
                    || !java.util.Objects.equals(view.source(), device.displaySource) || view.head() != device.displayHead) {
                device.displayWidth = view.width();
                device.displayHeight = view.height();
                device.displayPort = view.port();
                device.displaySource = view.source();
                device.displayHead = view.head();
                device.setChanged();
                level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
            }
        }
        long leds = manager.network().ledState(device.pcId, block.deviceType());
        int link = (int) leds;
        int activity = (int) (leds >>> 32);
        if (link != device.linkLeds || activity != device.activityLeds) {
            device.linkLeds = link;
            device.activityLeds = activity;
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /** Called by the block when it is broken or replaced (1.21.1 has no block entity hook for this). */
    public void onBlockRemoved(BlockPos pos) {
        VmManager manager = VmManager.get();
        if (pcId != null && manager != null && level instanceof ServerLevel serverLevel) {
            manager.onPcRemoved(pcId);
            manager.network().deviceRemoved(pcId, serverLevel, pos);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        pcId = tag.hasUUID("pc_id") ? tag.getUUID("pc_id") : null;
        linkLeds = tag.getInt("link_leds");
        activityLeds = tag.getInt("activity_leds");
        ioOutputs = tag.getInt("io_outputs");
        displayWidth = tag.contains("display_width") ? tag.getInt("display_width") : 2;
        displayHeight = tag.contains("display_height") ? tag.getInt("display_height") : 1;
        displayPort = tag.getInt("display_port");
        displaySource = tag.hasUUID("display_source") ? tag.getUUID("display_source") : null;
        displayHead = tag.getInt("display_head");
        usbEnabled = !tag.contains("usb_enabled") || tag.getBoolean("usb_enabled");
        hdmiMirrored = tag.getBoolean("hdmi_mirrored");
        micMode = tag.getInt("mic_mode");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (pcId != null) {
            tag.putUUID("pc_id", pcId);
        }
        tag.putInt("link_leds", linkLeds);
        tag.putInt("activity_leds", activityLeds);
        tag.putInt("io_outputs", ioOutputs);
        tag.putInt("display_width", displayWidth);
        tag.putInt("display_height", displayHeight);
        tag.putInt("display_port", displayPort);
        if (displaySource != null) {
            tag.putUUID("display_source", displaySource);
        }
        tag.putInt("display_head", displayHead);
        tag.putBoolean("usb_enabled", usbEnabled);
        tag.putBoolean("hdmi_mirrored", hdmiMirrored);
        tag.putInt("mic_mode", micMode);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    protected void applyImplicitComponents(BlockEntity.DataComponentInput components) {
        super.applyImplicitComponents(components);
        UUID fromItem = components.get(ModRegistry.PC_ID);
        if (fromItem != null) {
            pcId = fromItem;
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (pcId != null) {
            components.set(ModRegistry.PC_ID, pcId);
        }
    }

    @Override
    public void removeComponentsFromTag(CompoundTag tag) {
        super.removeComponentsFromTag(tag);
        tag.remove("pc_id");
    }
}

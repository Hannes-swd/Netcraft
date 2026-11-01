package dev.hannes.netcraft_pc_builder.block;

import dev.hannes.netcraft_pc_builder.PcBuilderRegistry;
import dev.hannes.netcraft_pc_builder.pc.BuiltPcs;
import dev.hannes.netcraft_pc_builder.pc.CaseSize;
import dev.hannes.netcraft_pc_builder.pc.PcBuild;
import dev.hannes.virtual_mashine.block.DeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Netcraft device (machine id, cables, LEDs ...) that also holds the parts built into its case:
 * slot key to part id, saved, synced to clients and kept in the dropped item.
 */
public class CaseBlockEntity extends DeviceBlockEntity {
    private final Map<String, String> parts = new LinkedHashMap<>();
    /** The parts changed since the machine was last told. */
    private boolean partsDirty = true;

    public CaseBlockEntity(BlockPos pos, BlockState state) {
        super(PcBuilderRegistry.CASE_BLOCK_ENTITY, pos, state);
    }

    public PcBuild build() {
        CaseSize size = getBlockState().getBlock() instanceof CaseBlock block ? block.size() : CaseSize.MEDIUM;
        return new PcBuild(size, Collections.unmodifiableMap(parts));
    }

    /** Puts a part into a slot (null = takes it out). */
    public void setPart(String slot, @Nullable String partId) {
        if (partId == null) {
            parts.remove(slot);
        } else {
            parts.put(slot, partId);
        }
        partsDirty = true;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** Netcraft's device tick, then tell the machine what is built in. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, CaseBlockEntity entity) {
        DeviceBlockEntity.serverTick(level, pos, state, entity);
        if (entity.partsDirty && entity.getPcId() != null) {
            BuiltPcs.update(entity.getPcId(), entity.build());
            entity.partsDirty = false;
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        parts.clear();
        CompoundTag saved = tag.getCompound("build");
        for (String slot : saved.getAllKeys()) {
            parts.put(slot, saved.getString(slot));
        }
        partsDirty = true;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!parts.isEmpty()) {
            CompoundTag saved = new CompoundTag();
            parts.forEach(saved::putString);
            tag.put("build", saved);
        }
    }

    @Override
    protected void applyImplicitComponents(DataComponentInput components) {
        super.applyImplicitComponents(components);
        Map<String, String> fromItem = components.get(PcBuilderRegistry.PC_BUILD);
        if (fromItem != null) {
            parts.clear();
            parts.putAll(fromItem);
            partsDirty = true;
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (!parts.isEmpty()) {
            components.set(PcBuilderRegistry.PC_BUILD, Map.copyOf(parts));
        }
    }

    @Override
    public void removeComponentsFromTag(CompoundTag tag) {
        super.removeComponentsFromTag(tag);
        tag.remove("build");
    }
}

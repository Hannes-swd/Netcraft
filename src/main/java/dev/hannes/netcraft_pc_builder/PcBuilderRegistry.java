package dev.hannes.netcraft_pc_builder;

import com.mojang.serialization.Codec;
import dev.hannes.netcraft_pc_builder.block.CaseBlock;
import dev.hannes.netcraft_pc_builder.block.CaseBlockEntity;
import dev.hannes.netcraft_pc_builder.block.PartFabricatorBlock;
import dev.hannes.netcraft_pc_builder.item.PartTemplateItem;
import dev.hannes.netcraft_pc_builder.item.PcPartItem;
import dev.hannes.netcraft_pc_builder.pc.CaseSize;
import dev.hannes.netcraft_pc_builder.pc.PartCategory;
import dev.hannes.netcraft_pc_builder.pc.PcPart;
import dev.hannes.netcraft_pc_builder.pc.PcParts;
import dev.hannes.virtual_mashine.block.DeviceBlock;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import java.util.EnumMap;
import java.util.Map;

public final class PcBuilderRegistry {
    /** Slot to part id of the parts in a case; travels with the dropped case. */
    public static final DataComponentType<Map<String, String>> PC_BUILD = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            PcBuilder.id("pc_build"),
            DataComponentType.<Map<String, String>>builder()
                    .persistent(Codec.unboundedMap(Codec.STRING, Codec.STRING))
                    .networkSynchronized(ByteBufCodecs.<io.netty.buffer.ByteBuf, String, String, Map<String, String>>map(
                            java.util.HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.STRING_UTF8))
                    .build()
    );

    /** Which real part a PC part item is (id from the pc_parts data files). */
    public static final DataComponentType<String> PC_PART = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            PcBuilder.id("pc_part"),
            DataComponentType.<String>builder().persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build()
    );

    public static final Map<PartCategory, Item> PART_ITEMS = new EnumMap<>(PartCategory.class);
    /** Blank templates, one per category: made into a real part in the part fabricator. */
    public static final Map<PartCategory, Item> PART_TEMPLATES = new EnumMap<>(PartCategory.class);
    /** Buildable PCs: Netcraft computers whose hardware are the parts built in. */
    public static final Map<CaseSize, Block> CASE_BLOCKS = new EnumMap<>(CaseSize.class);

    static {
        for (PartCategory category : PartCategory.values()) {
            PART_ITEMS.put(category, item(category.id, new PcPartItem(category, new Item.Properties().stacksTo(16))));
            PART_TEMPLATES.put(category, item(category.id + "_template", new PartTemplateItem(new Item.Properties())));
        }
        for (CaseSize size : CaseSize.values()) {
            Block block = Registry.register(BuiltInRegistries.BLOCK, PcBuilder.id(size.id), new CaseBlock(size,
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_BLACK)
                            .strength(2.0F, 6.0F)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
                            .noOcclusion()
                            .isRedstoneConductor((state, level, pos) -> false)
                            .lightLevel(state -> state.getValue(DeviceBlock.LIT) ? 5 : 0)));
            CASE_BLOCKS.put(size, block);
            item(size.id, new BlockItem(block, new Item.Properties().stacksTo(1)));
        }
    }

    public static final BlockEntityType<CaseBlockEntity> CASE_BLOCK_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            PcBuilder.id("case"),
            FabricBlockEntityTypeBuilder.create(CaseBlockEntity::new, CASE_BLOCKS.values().toArray(new Block[0])).build()
    );

    public static final Block PART_FABRICATOR_BLOCK = Registry.register(BuiltInRegistries.BLOCK, PcBuilder.id("part_fabricator"),
            new PartFabricatorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(2.5F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));
    public static final Item PART_FABRICATOR = item("part_fabricator", new BlockItem(PART_FABRICATOR_BLOCK, new Item.Properties()));

    private PcBuilderRegistry() {
    }

    private static Item item(String name, Item item) {
        return Registry.register(BuiltInRegistries.ITEM, PcBuilder.id(name), item);
    }

    /** One tab for cases, fabricator and templates, then one tab per kind of part. */
    public static void register() {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, ResourceKey.create(Registries.CREATIVE_MODE_TAB, PcBuilder.id("pc_parts")),
                FabricItemGroup.builder()
                        .title(Component.translatable("itemGroup.netcraft_pc_builder.pc_parts"))
                        .icon(() -> new ItemStack(CASE_BLOCKS.get(CaseSize.MEDIUM)))
                        .displayItems((parameters, output) -> {
                            for (Block block : CASE_BLOCKS.values()) {
                                output.accept(block);
                            }
                            output.accept(PART_FABRICATOR);
                            for (Item template : PART_TEMPLATES.values()) {
                                output.accept(template);
                            }
                        })
                        .build());
        for (PartCategory category : PartCategory.values()) {
            ResourceKey<CreativeModeTab> key = ResourceKey.create(Registries.CREATIVE_MODE_TAB, PcBuilder.id("pc_" + category.id));
            Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, key, FabricItemGroup.builder()
                    .title(Component.translatable("itemGroup.netcraft_pc_builder.pc_" + category.id))
                    .icon(() -> PcParts.of(category).isEmpty() ? new ItemStack(PART_ITEMS.get(category))
                            : PcPartItem.stackOf(PcParts.of(category).get(PcParts.of(category).size() - 1)))
                    .displayItems((parameters, output) -> {
                        output.accept(PART_TEMPLATES.get(category));
                        for (PcPart part : PcParts.of(category)) {
                            output.accept(PcPartItem.stackOf(part));
                        }
                    })
                    .build());
        }
    }
}

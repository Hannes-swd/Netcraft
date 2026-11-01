package dev.hannes.virtual_mashine;

import com.mojang.serialization.Codec;
import dev.hannes.virtual_mashine.block.CaseBlock;
import dev.hannes.virtual_mashine.block.CaseBlockEntity;
import dev.hannes.virtual_mashine.block.CableClipBlock;
import dev.hannes.virtual_mashine.block.DeviceBlock;
import dev.hannes.virtual_mashine.block.DeviceBlockEntity;
import dev.hannes.virtual_mashine.block.RackBlock;
import dev.hannes.virtual_mashine.block.RackBlockEntity;
import dev.hannes.virtual_mashine.device.CableType;
import dev.hannes.virtual_mashine.device.DeviceType;
import dev.hannes.virtual_mashine.item.CableItem;
import dev.hannes.virtual_mashine.item.NetworkTesterItem;
import dev.hannes.virtual_mashine.item.PhoneItem;
import dev.hannes.virtual_mashine.item.PcPartItem;
import dev.hannes.virtual_mashine.pc.CaseSize;
import dev.hannes.virtual_mashine.pc.PartCategory;
import dev.hannes.virtual_mashine.pc.PcPart;
import dev.hannes.virtual_mashine.pc.PcParts;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.UUIDUtil;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public final class ModRegistry {
    /** Which machine a device block (or its dropped item) belongs to. */
    public static final DataComponentType<UUID> PC_ID = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            Virtual_mashine.id("pc_id"),
            DataComponentType.<UUID>builder().persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC).build()
    );

    /** Jacket colour of a cable item (RGB). */
    public static final DataComponentType<Integer> CABLE_COLOR = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            Virtual_mashine.id("cable_color"),
            DataComponentType.<Integer>builder().persistent(Codec.INT).networkSynchronized(ByteBufCodecs.INT).build()
    );

    /** Which real part a PC part item is (id from the pc_parts data files). */
    public static final DataComponentType<String> PC_PART = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            Virtual_mashine.id("pc_part"),
            DataComponentType.<String>builder().persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build()
    );

    public static final Map<DeviceType, Block> DEVICE_BLOCKS = new EnumMap<>(DeviceType.class);
    public static final Map<DeviceType, Item> DEVICE_ITEMS = new EnumMap<>(DeviceType.class);
    public static final Map<CableType, Item> CABLES = new EnumMap<>(CableType.class);

    public static final Block PC_BLOCK = device(DeviceType.PC, Shapes.or(Block.box(0, 0, 0, 16, 5, 16), Block.box(2, 5, 2, 14, 15, 14)), MapColor.SAND, 7);
    public static final Block SERVER_BLOCK = device(DeviceType.SERVER, Block.box(0, 0, 0, 16, 16, 16), MapColor.COLOR_GRAY, 3);
    public static final Block ROUTER_BLOCK = device(DeviceType.ROUTER, Block.box(2, 0, 2, 14, 3, 14), MapColor.COLOR_BLACK, 3);
    public static final Block MANAGED_SWITCH_BLOCK = device(DeviceType.MANAGED_SWITCH, Block.box(0, 0, 0, 16, 4, 16), MapColor.COLOR_BLUE, 3);
    public static final Block SWITCH_BLOCK = device(DeviceType.SWITCH, Block.box(0, 0, 0, 16, 4, 16), MapColor.METAL, 3);
    public static final Block HUB_BLOCK = device(DeviceType.HUB, Block.box(0, 0, 0, 16, 4, 16), MapColor.TERRACOTTA_WHITE, 3);
    public static final Block MODEM_BLOCK = device(DeviceType.MODEM, Block.box(4, 0, 4, 12, 7, 12), MapColor.SNOW, 3);
    public static final Block LAPTOP_BLOCK = device(DeviceType.LAPTOP, Block.box(2, 0, 2, 14, 9, 14), MapColor.COLOR_GRAY, 5);
    public static final Block ACCESS_POINT_BLOCK = device(DeviceType.ACCESS_POINT, Block.box(4, 0, 4, 12, 3, 12), MapColor.SNOW, 3);
    public static final Block IO_MODULE_BLOCK = device(DeviceType.IO_MODULE, Shapes.block(), MapColor.COLOR_LIGHT_GRAY, 2);
    public static final Block DISPLAY_BLOCK = device(DeviceType.DISPLAY, Shapes.block(), MapColor.COLOR_BLACK, 4);
    public static final Block MICROPHONE_BLOCK = device(DeviceType.MICROPHONE,
            Shapes.or(Block.box(5, 0, 5, 11, 1, 11), Block.box(7, 1, 7, 9, 7, 9), Block.box(6, 7, 6, 10, 12, 10)), MapColor.COLOR_GRAY, 0);
    public static final Block CAMERA_BLOCK = device(DeviceType.CAMERA,
            Shapes.or(Block.box(6, 0, 6, 10, 3, 10), Block.box(4, 3, 4, 12, 9, 12)), MapColor.COLOR_BLACK, 1);
    public static final Block HDMI_SWITCH_BLOCK = device(DeviceType.HDMI_SWITCH, Block.box(3, 0, 4, 13, 3, 12), MapColor.COLOR_BLACK, 1);
    public static final Block USB_HUB_BLOCK = device(DeviceType.USB_HUB, Block.box(4, 0, 5, 12, 2, 11), MapColor.COLOR_GRAY, 1);
    public static final Block SPEAKER_BLOCK = device(DeviceType.SPEAKER, Block.box(4, 0, 4, 12, 14, 12), MapColor.COLOR_BLACK, 1);

    public static final Item PC_ITEM = DEVICE_ITEMS.get(DeviceType.PC);

    static {
        for (CableType type : CableType.values()) {
            CABLES.put(type, item(type.id + "_cable", properties -> new CableItem(type, properties)));
        }
    }

    public static final Block CABLE_CLIP_BLOCK = Registry.register(BuiltInRegistries.BLOCK, Virtual_mashine.id("cable_clip"),
            new CableClipBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(0.3F)
                    .sound(SoundType.STONE)
                    .noCollission()
                    .noOcclusion()));
    public static final Item CABLE_CLIP = item("cable_clip", properties -> new BlockItem(CABLE_CLIP_BLOCK, properties));

    public static final Block RACK_BLOCK = Registry.register(BuiltInRegistries.BLOCK, Virtual_mashine.id("rack"),
            new RackBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .dynamicShape() // the hitbox follows the mounted devices
                    .strength(2.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));
    public static final Item RACK = item("rack", properties -> new BlockItem(RACK_BLOCK, properties));
    public static final BlockEntityType<RackBlockEntity> RACK_BLOCK_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Virtual_mashine.id("rack"),
            FabricBlockEntityTypeBuilder.create(RackBlockEntity::new, RACK_BLOCK).build()
    );

    public static final Item NETWORK_TESTER = item("network_tester", NetworkTesterItem::new);
    public static final Item PHONE = item("phone", properties -> new PhoneItem(properties.stacksTo(1)));

    /** Registry id stays "pc" so PCs from earlier versions keep their machine. */
    public static final BlockEntityType<DeviceBlockEntity> DEVICE_BLOCK_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Virtual_mashine.id("pc"),
            FabricBlockEntityTypeBuilder.create(DeviceBlockEntity::new, DEVICE_BLOCKS.values().toArray(new Block[0])).build()
    );


    public static final Map<PartCategory, Item> PART_ITEMS = new EnumMap<>(PartCategory.class);
    public static final Map<CaseSize, Block> CASE_BLOCKS = new EnumMap<>(CaseSize.class);

    static {
        for (PartCategory category : PartCategory.values()) {
            PART_ITEMS.put(category, item(category.id, properties -> new PcPartItem(category, properties.stacksTo(16))));
        }
        for (CaseSize size : CaseSize.values()) {
            Block block = Registry.register(BuiltInRegistries.BLOCK, Virtual_mashine.id(size.id), new CaseBlock(size,
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_BLACK)
                            .strength(2.0F, 6.0F)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
                            .noOcclusion()));
            CASE_BLOCKS.put(size, block);
            Registry.register(BuiltInRegistries.ITEM, Virtual_mashine.id(size.id), new BlockItem(block, new Item.Properties()));
        }
    }

    public static final BlockEntityType<CaseBlockEntity> CASE_BLOCK_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Virtual_mashine.id("pc_case"),
            FabricBlockEntityTypeBuilder.create(CaseBlockEntity::new, CASE_BLOCKS.values().toArray(new Block[0])).build()
    );

    private ModRegistry() {
    }

    /** One creative tab per category, like the vanilla ones. */
    public static void register() {
        tab("devices", DEVICE_ITEMS.get(DeviceType.PC), DEVICE_ITEMS.get(DeviceType.PC), DEVICE_ITEMS.get(DeviceType.LAPTOP),
                PHONE, DEVICE_ITEMS.get(DeviceType.SERVER), DEVICE_ITEMS.get(DeviceType.DISPLAY), DEVICE_ITEMS.get(DeviceType.HDMI_SWITCH),
                DEVICE_ITEMS.get(DeviceType.USB_HUB), DEVICE_ITEMS.get(DeviceType.MICROPHONE), DEVICE_ITEMS.get(DeviceType.CAMERA),
                DEVICE_ITEMS.get(DeviceType.SPEAKER));
        tab("network_devices", DEVICE_ITEMS.get(DeviceType.SWITCH), DEVICE_ITEMS.get(DeviceType.SWITCH), DEVICE_ITEMS.get(DeviceType.HUB),
                DEVICE_ITEMS.get(DeviceType.MANAGED_SWITCH), DEVICE_ITEMS.get(DeviceType.ROUTER), DEVICE_ITEMS.get(DeviceType.ACCESS_POINT), RACK);
        tab("internet", DEVICE_ITEMS.get(DeviceType.MODEM), DEVICE_ITEMS.get(DeviceType.MODEM));
        tab("automation", DEVICE_ITEMS.get(DeviceType.IO_MODULE), DEVICE_ITEMS.get(DeviceType.IO_MODULE),
                DEVICE_ITEMS.get(DeviceType.DISPLAY));
        java.util.List<Item> cablesAndTools = new java.util.ArrayList<>(CABLES.values());
        cablesAndTools.add(CABLE_CLIP);
        cablesAndTools.add(NETWORK_TESTER);
        tab("cables", CABLES.get(CableType.CAT6), cablesAndTools.toArray(new Item[0]));
        ResourceKey<CreativeModeTab> parts = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Virtual_mashine.id("pc_parts"));
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, parts, FabricItemGroup.builder()
                .title(Component.translatable("itemGroup.virtual_mashine.pc_parts"))
                .icon(() -> new ItemStack(CASE_BLOCKS.get(CaseSize.MEDIUM)))
                .displayItems((parameters, output) -> {
                    for (Block block : CASE_BLOCKS.values()) {
                        output.accept(block);
                    }
                    for (PartCategory category : PartCategory.values()) {
                        for (PcPart part : PcParts.of(category)) {
                            output.accept(PcPartItem.stackOf(part));
                        }
                    }
                })
                .build());
    }

    private static void tab(String name, Item icon, Item... items) {
        ResourceKey<CreativeModeTab> key = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Virtual_mashine.id(name));
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, key, FabricItemGroup.builder()
                .title(Component.translatable("itemGroup.virtual_mashine." + name))
                .icon(() -> new ItemStack(icon))
                .displayItems((parameters, output) -> {
                    for (Item item : items) {
                        output.accept(item);
                    }
                })
                .build());
    }

    private static Block device(DeviceType type, VoxelShape shape, MapColor color, int light) {
        Block block = Registry.register(BuiltInRegistries.BLOCK, Virtual_mashine.id(type.id), new DeviceBlock(type, shape,
                BlockBehaviour.Properties.of()
                        .mapColor(color)
                        .strength(2.0F, 6.0F)
                        .sound(SoundType.METAL)
                        .requiresCorrectToolForDrops()
                        .noOcclusion()
                        // Not a conductor: an I/O module's faces carry exactly their own channel, nothing passes through.
                        .isRedstoneConductor((state, level, pos) -> false)
                        .lightLevel(state -> state.getValue(DeviceBlock.LIT) ? light : 0)));
        Item item = Registry.register(BuiltInRegistries.ITEM, Virtual_mashine.id(type.id), new BlockItem(block, new Item.Properties()));
        DEVICE_BLOCKS.put(type, block);
        DEVICE_ITEMS.put(type, item);
        return block;
    }

    private static Item item(String name, Function<Item.Properties, Item> factory) {
        return Registry.register(BuiltInRegistries.ITEM, Virtual_mashine.id(name), factory.apply(new Item.Properties()));
    }
}

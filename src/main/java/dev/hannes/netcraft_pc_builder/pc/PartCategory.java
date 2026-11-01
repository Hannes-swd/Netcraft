package dev.hannes.netcraft_pc_builder.pc;

import net.minecraft.network.chat.Component;

/** The kinds of PC parts; each has its own item and its own data file under data/netcraft_pc_builder/pc_parts. */
public enum PartCategory {
    MAINBOARD("mainboard", 0xFF1F6B3F),
    CPU("cpu", 0xFF9A9AA6),
    COOLER("cooler", 0xFF3F72AF),
    RAM("ram", 0xFF7B3A93),
    GPU("gpu", 0xFF3A3F46),
    STORAGE("storage", 0xFF2F5D8A),
    PSU("psu", 0xFF4D4D4D);

    public final String id;
    /** Fill colour of an installed part in the case screen. */
    public final int color;

    PartCategory(String id, int color) {
        this.id = id;
        this.color = color;
    }

    /** Translated name, e.g. "Graphics Card" / "Grafikkarte". */
    public Component displayName() {
        return Component.translatable("netcraft_pc_builder.category." + id);
    }
}

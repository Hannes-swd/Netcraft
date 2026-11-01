package dev.hannes.virtual_mashine.pc;

/** The kinds of PC parts; each has its own item and its own data file under data/virtual_mashine/pc_parts. */
public enum PartCategory {
    MAINBOARD("mainboard", "Mainboard", 0xFF1F6B3F),
    CPU("cpu", "CPU", 0xFF9A9AA6),
    COOLER("cooler", "CPU Cooler", 0xFF3F72AF),
    RAM("ram", "RAM", 0xFF7B3A93),
    GPU("gpu", "Graphics Card", 0xFF3A3F46),
    STORAGE("storage", "Storage", 0xFF2F5D8A),
    PSU("psu", "Power Supply", 0xFF4D4D4D);

    public final String id;
    public final String label;
    /** Fill colour of an installed part in the case screen. */
    public final int color;

    PartCategory(String id, String label, int color) {
        this.id = id;
        this.label = label;
        this.color = color;
    }
}

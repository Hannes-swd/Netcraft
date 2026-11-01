package dev.hannes.virtual_mashine.pc;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The three case blocks. Limits decide what fits; width/height are the size of the case
 * drawing in the build screen (in layout units).
 */
public enum CaseSize {
    SMALL("case_small", List.of("ITX"), 330, 155, 280, 0, 2, 250, 200),
    MEDIUM("case_medium", List.of("ATX", "mATX", "ITX"), 360, 170, 360, 2, 2, 330, 240),
    LARGE("case_large", List.of("ATX", "mATX", "ITX"), 420, 190, 420, 4, 4, 340, 260);

    public final String id;
    public final List<String> forms;
    public final int maxGpu;
    public final int maxCooler;
    public final int maxRadiator;
    public final int bays35;
    public final int bays25;
    public final int width;
    public final int height;

    public Component displayName() {
        return Component.translatable("block.virtual_mashine." + id);
    }

    CaseSize(String id, List<String> forms, int maxGpu, int maxCooler, int maxRadiator,
             int bays35, int bays25, int width, int height) {
        this.id = id;
        this.forms = forms;
        this.maxGpu = maxGpu;
        this.maxCooler = maxCooler;
        this.maxRadiator = maxRadiator;
        this.bays35 = bays35;
        this.bays25 = bays25;
        this.width = width;
        this.height = height;
    }
}

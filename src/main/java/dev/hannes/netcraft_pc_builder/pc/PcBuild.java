package dev.hannes.netcraft_pc_builder.pc;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What is installed in a case (slot key to part id) and the rules for it. Slot keys: mainboard, cpu,
 * cooler, ram0.., m2_0.., gpu, psu, bay35_0.., bay25_0... Board slots exist only with a mainboard.
 * Every reason is a translatable text, shown in the player's language.
 */
public final class PcBuild {
    private final CaseSize size;
    private final Map<String, String> parts;

    public PcBuild(CaseSize size, Map<String, String> parts) {
        this.size = size;
        this.parts = parts;
    }

    public CaseSize size() {
        return size;
    }

    public @Nullable PcPart get(String slot) {
        String id = parts.get(slot);
        return id == null ? null : PcParts.get(id);
    }

    private static Component t(String key, Object... args) {
        return Component.translatable("netcraft_pc_builder.build." + key, args);
    }

    public static @Nullable PartCategory categoryOf(String slot) {
        return switch (slot) {
            case "mainboard" -> PartCategory.MAINBOARD;
            case "cpu" -> PartCategory.CPU;
            case "cooler" -> PartCategory.COOLER;
            case "gpu" -> PartCategory.GPU;
            case "psu" -> PartCategory.PSU;
            default -> slot.startsWith("ram") ? PartCategory.RAM
                    : slot.startsWith("m2_") || slot.startsWith("bay") ? PartCategory.STORAGE : null;
        };
    }

    public static Component slotLabel(String slot) {
        if (slot.startsWith("m2_")) return Component.literal("M.2");
        if (slot.startsWith("bay35_")) return t("bay35");
        if (slot.startsWith("bay25_")) return t("bay25");
        PartCategory category = categoryOf(slot);
        return category == null ? Component.literal(slot) : category.displayName();
    }

    public List<String> slots() {
        List<String> list = new ArrayList<>();
        list.add("mainboard");
        PcPart board = get("mainboard");
        if (board != null) {
            list.add("cpu");
            list.add("cooler");
            for (int i = 0; i < board.num("ram_slots") / 2; i++) list.add("ram" + i);
            for (int i = 0; i < Math.min(4, board.num("m2")); i++) list.add("m2_" + i);
            list.add("gpu");
        }
        list.add("psu");
        for (int i = 0; i < size.bays35; i++) list.add("bay35_" + i);
        for (int i = 0; i < size.bays25; i++) list.add("bay25_" + i);
        return list;
    }

    private int sataUsed() {
        return (int) parts.keySet().stream().filter(k -> k.startsWith("bay")).count();
    }

    /** Null if the part can go into this slot, otherwise why not. */
    public @Nullable Component canInstall(String slot, PcPart part) {
        PartCategory category = categoryOf(slot);
        if (category == null || !slots().contains(slot)) return t("slot_unavailable");
        if (parts.containsKey(slot)) return t("slot_taken");
        if (part.category() != category) return t("wrong_slot", part.category().displayName());
        PcPart board = get("mainboard");
        PcPart cpu = get("cpu");
        switch (category) {
            case MAINBOARD -> {
                if (!size.forms.contains(part.str("form"))) return t("case_form", String.join(" / ", size.forms), part.str("form"));
            }
            case CPU -> {
                if (!part.str("socket").equals(board.str("socket"))) return t("cpu_socket", part.str("socket"), board.str("socket"));
                if (!part.strs("memory").contains(board.str("memory"))) return t("cpu_memory", board.str("memory"));
            }
            case COOLER -> {
                if (cpu == null) return t("need_cpu");
                if (!part.strs("sockets").contains(cpu.str("socket"))) return t("cooler_mount", cpu.str("socket"));
                if ("aio".equals(part.str("type"))) {
                    if (part.num("radiator_mm") > size.maxRadiator) return t("radiator", part.num("radiator_mm"), size.maxRadiator);
                } else if (part.num("height_mm") > size.maxCooler) {
                    return t("cooler_tall", part.num("height_mm"), size.maxCooler);
                }
            }
            case RAM -> {
                if (!part.str("memory").equals(board.str("memory"))) return t("ram_type", part.str("memory"), board.str("memory"));
            }
            case GPU -> {
                if (part.num("length_mm") > size.maxGpu) return t("gpu_long", part.num("length_mm"), size.maxGpu);
            }
            case STORAGE -> {
                String type = part.str("slot");
                if (slot.startsWith("m2_")) {
                    if (!type.equals("m2")) return t("m2_only");
                } else {
                    if (type.equals("m2")) return t("m2_board");
                    if (slot.startsWith("bay25_") && type.equals("sata35")) return t("bay_small");
                    if (board == null) return t("need_board_sata");
                    if (sataUsed() >= board.num("sata")) return t("no_sata");
                }
            }
            case PSU -> {
            }
        }
        return null;
    }

    /** Null if the part can be taken out, otherwise why not. */
    public @Nullable Component canRemove(String slot) {
        if (slot.equals("mainboard") && parts.keySet().stream().anyMatch(k ->
                k.equals("cpu") || k.startsWith("ram") || k.startsWith("m2_") || k.equals("gpu"))) {
            return t("remove_board_parts");
        }
        if (slot.equals("cpu") && parts.containsKey("cooler")) return t("remove_cooler");
        return null;
    }

    private boolean any(String prefix) {
        return parts.keySet().stream().anyMatch(k -> k.startsWith(prefix));
    }

    /** Parts still needed before the PC can start. */
    public List<Component> missing() {
        List<Component> list = new ArrayList<>();
        PcPart cpu = get("cpu");
        if (get("mainboard") == null) list.add(PartCategory.MAINBOARD.displayName());
        if (cpu == null) list.add(PartCategory.CPU.displayName());
        if (get("cooler") == null && (cpu == null || !cpu.bool("box_cooler"))) list.add(PartCategory.COOLER.displayName());
        if (!any("ram")) list.add(PartCategory.RAM.displayName());
        if (!any("m2_") && !any("bay")) list.add(PartCategory.STORAGE.displayName());
        if (get("psu") == null) list.add(PartCategory.PSU.displayName());
        if (cpu != null && get("gpu") == null && !cpu.bool("igpu")) list.add(t("missing_gpu"));
        return list;
    }

    public int powerDraw() {
        PcPart cpu = get("cpu");
        PcPart gpu = get("gpu");
        int drives = (int) parts.keySet().stream().filter(k -> k.startsWith("m2_") || k.startsWith("bay")).count();
        return 60 + (cpu == null ? 0 : cpu.num("tdp")) + (gpu == null ? 0 : gpu.num("power")) + drives * 5;
    }

    /** Everything is there but does not work together. */
    public List<Component> problems() {
        List<Component> list = new ArrayList<>();
        PcPart psu = get("psu");
        PcPart gpu = get("gpu");
        PcPart cpu = get("cpu");
        PcPart cooler = get("cooler");
        if (psu != null) {
            int watts = psu.num("watts");
            if (powerDraw() > watts) list.add(t("psu_weak", powerDraw()));
            if (gpu != null) {
                if (gpu.num("min_psu") > watts) list.add(t("gpu_psu", gpu.num("min_psu")));
                if (gpu.sub("connectors", "12vhpwr") > psu.num("12vhpwr")) list.add(t("no_16pin"));
                if (gpu.sub("connectors", "pcie8") + gpu.sub("connectors", "pcie6") > psu.num("pcie8")) list.add(t("few_pcie"));
            }
        }
        if (cpu != null && cooler != null && cooler.num("max_tdp") < cpu.num("tdp")) {
            list.add(t("cooler_weak", cooler.num("max_tdp"), cpu.num("tdp")));
        }
        return list;
    }

    /** The first reason why it cannot start ("Missing: ..." or a problem), null if it can. */
    public @Nullable Component blocker() {
        List<Component> missing = missing();
        if (!missing.isEmpty()) return t("missing", ComponentUtils.formatList(missing, Component.literal(", ")));
        List<Component> problems = problems();
        return problems.isEmpty() ? null : problems.get(0);
    }

    public boolean bootable() {
        return missing().isEmpty() && problems().isEmpty();
    }

    /** Virtual CPUs the machine gets: the threads of the CPU. */
    public int threads() {
        PcPart cpu = get("cpu");
        return cpu == null ? 0 : cpu.num("threads");
    }

    /** Total size of all drives in GB: the machine's disk. */
    public int storageGb() {
        int sum = 0;
        for (String slot : parts.keySet()) {
            if ((slot.startsWith("m2_") || slot.startsWith("bay")) && get(slot) instanceof PcPart drive) sum += drive.num("size_gb");
        }
        return sum;
    }

    public int ramGb() {
        int sum = 0;
        for (String slot : parts.keySet()) {
            if (slot.startsWith("ram") && get(slot) instanceof PcPart ram) sum += ram.num("size_gb");
        }
        return sum;
    }
}

package dev.hannes.virtual_mashine.pc;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What is installed in a case (slot key to part id) and the rules for it. Slot keys: mainboard, cpu,
 * cooler, ram0.., m2_0.., gpu, psu, bay35_0.., bay25_0... Board slots exist only with a mainboard.
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

    public static String slotLabel(String slot) {
        if (slot.startsWith("m2_")) return "M.2";
        if (slot.startsWith("bay35_")) return "3.5\" Bay";
        if (slot.startsWith("bay25_")) return "2.5\" Bay";
        PartCategory category = categoryOf(slot);
        return category == null ? slot : category.label;
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
    public @Nullable String canInstall(String slot, PcPart part) {
        PartCategory category = categoryOf(slot);
        if (category == null || !slots().contains(slot)) return "This slot is not available";
        if (parts.containsKey(slot)) return "There is already a part in this slot";
        if (part.category() != category) return "A " + part.category().label + " does not go here";
        PcPart board = get("mainboard");
        PcPart cpu = get("cpu");
        switch (category) {
            case MAINBOARD -> {
                if (!size.forms.contains(part.str("form"))) {
                    return "This case only fits " + String.join(" / ", size.forms) + " mainboards (this one is " + part.str("form") + ")";
                }
            }
            case CPU -> {
                if (!part.str("socket").equals(board.str("socket"))) {
                    return "A " + part.str("socket") + " CPU does not fit the " + board.str("socket") + " socket";
                }
                if (!part.strs("memory").contains(board.str("memory"))) {
                    return "This CPU does not support " + board.str("memory") + " memory";
                }
            }
            case COOLER -> {
                if (cpu == null) return "Install a CPU first";
                if (!part.strs("sockets").contains(cpu.str("socket"))) return "This cooler has no mount for " + cpu.str("socket");
                if ("aio".equals(part.str("type"))) {
                    if (part.num("radiator_mm") > size.maxRadiator) {
                        return "Radiator too big (" + part.num("radiator_mm") + " mm, case fits " + size.maxRadiator + " mm)";
                    }
                } else if (part.num("height_mm") > size.maxCooler) {
                    return "Cooler too tall (" + part.num("height_mm") + " mm, case fits " + size.maxCooler + " mm)";
                }
            }
            case RAM -> {
                if (!part.str("memory").equals(board.str("memory"))) {
                    return part.str("memory") + " RAM does not fit a " + board.str("memory") + " mainboard";
                }
            }
            case GPU -> {
                if (part.num("length_mm") > size.maxGpu) {
                    return "Card too long (" + part.num("length_mm") + " mm, case fits " + size.maxGpu + " mm)";
                }
            }
            case STORAGE -> {
                String type = part.str("slot");
                if (slot.startsWith("m2_")) {
                    if (!type.equals("m2")) return "Only M.2 SSDs go into an M.2 slot";
                } else {
                    if (type.equals("m2")) return "An M.2 SSD goes onto the mainboard";
                    if (slot.startsWith("bay25_") && type.equals("sata35")) return "A 3.5\" drive does not fit a 2.5\" bay";
                    if (board == null) return "Install a mainboard first (SATA cable)";
                    if (sataUsed() >= board.num("sata")) return "No free SATA port on the mainboard";
                }
            }
            case PSU -> {
            }
        }
        return null;
    }

    /** Null if the part can be taken out, otherwise why not. */
    public @Nullable String canRemove(String slot) {
        if (slot.equals("mainboard") && parts.keySet().stream().anyMatch(k ->
                k.equals("cpu") || k.startsWith("ram") || k.startsWith("m2_") || k.equals("gpu"))) {
            return "Take out the parts on the mainboard first";
        }
        if (slot.equals("cpu") && parts.containsKey("cooler")) return "Take off the cooler first";
        return null;
    }

    private boolean any(String prefix) {
        return parts.keySet().stream().anyMatch(k -> k.startsWith(prefix));
    }

    /** Parts still needed before the PC can start. */
    public List<String> missing() {
        List<String> list = new ArrayList<>();
        PcPart cpu = get("cpu");
        if (get("mainboard") == null) list.add("Mainboard");
        if (cpu == null) list.add("CPU");
        if (get("cooler") == null && (cpu == null || !cpu.bool("box_cooler"))) list.add("CPU Cooler");
        if (!any("ram")) list.add("RAM");
        if (!any("m2_") && !any("bay")) list.add("Storage");
        if (get("psu") == null) list.add("Power Supply");
        if (cpu != null && get("gpu") == null && !cpu.bool("igpu")) list.add("Graphics Card (CPU has no integrated graphics)");
        return list;
    }

    public int powerDraw() {
        PcPart cpu = get("cpu");
        PcPart gpu = get("gpu");
        int drives = (int) parts.keySet().stream().filter(k -> k.startsWith("m2_") || k.startsWith("bay")).count();
        return 60 + (cpu == null ? 0 : cpu.num("tdp")) + (gpu == null ? 0 : gpu.num("power")) + drives * 5;
    }

    /** Everything is there but does not work together. */
    public List<String> problems() {
        List<String> list = new ArrayList<>();
        PcPart psu = get("psu");
        PcPart gpu = get("gpu");
        PcPart cpu = get("cpu");
        PcPart cooler = get("cooler");
        if (psu != null) {
            int watts = psu.num("watts");
            if (powerDraw() > watts) list.add("Power supply too weak (" + powerDraw() + " W needed)");
            if (gpu != null) {
                if (gpu.num("min_psu") > watts) list.add("The graphics card needs a " + gpu.num("min_psu") + " W power supply");
                if (gpu.sub("connectors", "12vhpwr") > psu.num("12vhpwr")) list.add("Power supply has no 16-pin cable for the card");
                if (gpu.sub("connectors", "pcie8") + gpu.sub("connectors", "pcie6") > psu.num("pcie8")) {
                    list.add("Power supply has too few PCIe cables");
                }
            }
        }
        if (cpu != null && cooler != null && cooler.num("max_tdp") < cpu.num("tdp")) {
            list.add("Cooler too weak for this CPU (" + cooler.num("max_tdp") + " W < " + cpu.num("tdp") + " W)");
        }
        return list;
    }

    public boolean bootable() {
        return missing().isEmpty() && problems().isEmpty();
    }

    /** Virtual CPUs the machine gets: the threads of the CPU. */
    public int threads() {
        PcPart cpu = get("cpu");
        return cpu == null ? 0 : cpu.num("threads");
    }

    public int ramGb() {
        int sum = 0;
        for (String slot : parts.keySet()) {
            if (slot.startsWith("ram") && get(slot) instanceof PcPart ram) sum += ram.num("size_gb");
        }
        return sum;
    }
}

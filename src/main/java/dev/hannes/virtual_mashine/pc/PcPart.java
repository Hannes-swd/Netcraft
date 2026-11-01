package dev.hannes.virtual_mashine.pc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One real-world part from the data files; the fields depend on the category. */
public record PcPart(PartCategory category, String id, String brand, String name, JsonObject data) {
    public String str(String key) {
        JsonElement e = data.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    public int num(String key) {
        JsonElement e = data.get(key);
        return e == null || !e.isJsonPrimitive() ? 0 : e.getAsInt();
    }

    public double dbl(String key) {
        JsonElement e = data.get(key);
        return e == null || !e.isJsonPrimitive() ? 0 : e.getAsDouble();
    }

    public boolean bool(String key) {
        JsonElement e = data.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    public List<String> strs(String key) {
        List<String> out = new ArrayList<>();
        JsonElement e = data.get(key);
        if (e instanceof JsonArray array) {
            array.forEach(item -> out.add(item.getAsString()));
        } else if (e != null && e.isJsonPrimitive()) {
            out.add(e.getAsString());
        }
        return out;
    }

    /** A number inside a nested object, e.g. connectors.pcie8; 0 when missing. */
    public int sub(String object, String key) {
        JsonElement e = data.get(object);
        if (e instanceof JsonObject o && o.get(key) instanceof JsonElement v && v.isJsonPrimitive()) {
            return v.getAsInt();
        }
        return 0;
    }

    public String fullName() {
        return brand + " " + name;
    }

    private static Component t(String key, Object... args) {
        return Component.translatable("virtual_mashine.part." + key, args);
    }

    private static Component yesNo(boolean value) {
        return Component.translatable(value ? "gui.yes" : "gui.no");
    }

    /** The lines shown in the tooltip while Shift is held. */
    public List<Component> details() {
        List<Component> l = new ArrayList<>();
        switch (category) {
            case CPU -> {
                l.add(t("socket", str("socket")));
                l.add(t("cores", num("cores"), num("threads")));
                l.add(t("clock", String.format(Locale.ROOT, "%.1f", dbl("base_ghz")), String.format(Locale.ROOT, "%.1f", dbl("boost_ghz"))));
                l.add(t("tdp", num("tdp")));
                l.add(t("memory", String.join(" / ", strs("memory"))));
                l.add(t("igpu", yesNo(bool("igpu"))));
                l.add(t("box_cooler", yesNo(bool("box_cooler"))));
            }
            case MAINBOARD -> {
                l.add(t("socket_chipset", str("socket"), str("chipset")));
                l.add(t("form", str("form")));
                l.add(t("board_memory", str("memory"), num("ram_slots")));
                l.add(t("board_slots", num("m2"), num("sata")));
            }
            case RAM -> {
                l.add(t("ram_type", str("memory") + "-" + num("speed")));
                l.add(t("ram_size", num("size_gb"), num("modules")));
            }
            case GPU -> {
                l.add(t("vram", num("vram_gb")));
                l.add(t("gpu_power", num("power"), num("min_psu")));
                List<String> plugs = new ArrayList<>();
                if (sub("connectors", "12vhpwr") > 0) plugs.add(sub("connectors", "12vhpwr") + "x 16-pin");
                if (sub("connectors", "pcie8") > 0) plugs.add(sub("connectors", "pcie8") + "x 8-pin");
                if (sub("connectors", "pcie6") > 0) plugs.add(sub("connectors", "pcie6") + "x 6-pin");
                l.add(t("plugs", plugs.isEmpty() ? t("plugs_none") : Component.literal(String.join(", ", plugs))));
                l.add(t("length", num("length_mm")));
            }
            case PSU -> {
                l.add(t("psu_power", num("watts"), str("rating")));
                l.add(t("psu_cables", num("pcie8"), num("12vhpwr")));
            }
            case COOLER -> {
                boolean aio = "aio".equals(str("type"));
                l.add(aio ? t("cooler_aio", num("radiator_mm")) : t("cooler_air", num("height_mm")));
                l.add(t("sockets", String.join(", ", strs("sockets"))));
                l.add(t("capacity", num("max_tdp")));
            }
            case STORAGE -> {
                String slot = str("slot");
                l.add(t("interface", switch (slot) {
                    case "m2" -> Component.literal("M.2 NVMe");
                    case "sata25" -> Component.literal("SATA 2.5\"");
                    default -> t("hdd");
                }));
                l.add(t("size", num("size_gb") >= 1000 ? num("size_gb") / 1000 + " TB" : num("size_gb") + " GB"));
                l.add(t("read", num("read_mbs")));
            }
        }
        return l;
    }
}

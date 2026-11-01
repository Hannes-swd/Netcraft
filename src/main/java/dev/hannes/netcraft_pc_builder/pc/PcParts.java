package dev.hannes.netcraft_pc_builder.pc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hannes.netcraft_pc_builder.PcBuilder;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All parts, read once from the mod's own data files (same on server and client). */
public final class PcParts {
    private static final Map<String, PcPart> BY_ID = new LinkedHashMap<>();
    private static final Map<PartCategory, List<PcPart>> BY_CATEGORY = new EnumMap<>(PartCategory.class);

    static {
        for (PartCategory category : PartCategory.values()) {
            List<PcPart> list = new ArrayList<>();
            String path = "/data/netcraft_pc_builder/pc_parts/" + category.id + ".json";
            try (InputStream in = PcParts.class.getResourceAsStream(path)) {
                if (in == null) {
                    PcBuilder.LOGGER.warn("Missing part list {}", path);
                } else {
                    JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                    for (JsonElement element : root.getAsJsonArray("parts")) {
                        JsonObject o = element.getAsJsonObject();
                        PcPart part = new PcPart(category, o.get("id").getAsString(), o.get("brand").getAsString(),
                                o.get("name").getAsString(), o);
                        list.add(part);
                        BY_ID.put(part.id(), part);
                    }
                }
            } catch (Exception e) {
                PcBuilder.LOGGER.error("Could not read part list {}", path, e);
            }
            BY_CATEGORY.put(category, Collections.unmodifiableList(list));
        }
    }

    private PcParts() {
    }

    public static @Nullable PcPart get(String id) {
        return BY_ID.get(id);
    }

    public static List<PcPart> of(PartCategory category) {
        return BY_CATEGORY.get(category);
    }
}

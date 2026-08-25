package in.rentradar.pipeline.provider.rentomojo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * RentoMojo's product pages are Nuxt SSR: the server response embeds the full
 * page state in a {@code <script id="__NUXT_DATA__">} block, serialized in
 * devalue's flat-array format — every object field and array element is an
 * integer index into one flat array. This class inflates that back into a
 * normal JSON tree. Reading it is parsing the permitted page we already
 * fetched; it involves no extra endpoint (PRD section 14, "Not an API").
 */
public final class NuxtDataParser {

    private static final Set<String> WRAPPER_MARKERS = Set.of("Reactive", "ShallowReactive", "Ref", "ShallowRef");
    private static final int MAX_DEPTH = 64;

    private final ArrayNode flat;
    private final Map<Integer, JsonNode> memo = new HashMap<>();

    private NuxtDataParser(ArrayNode flat) {
        this.flat = flat;
    }

    public static NuxtDataParser fromDocument(Document document) {
        Element script = document.selectFirst("script#__NUXT_DATA__");
        if (script == null) {
            throw new IllegalStateException("no __NUXT_DATA__ block — page structure changed");
        }
        try {
            JsonNode parsed = new ObjectMapper().readTree(script.data());
            if (!(parsed instanceof ArrayNode array)) {
                throw new IllegalStateException("__NUXT_DATA__ is not a flat array");
            }
            return new NuxtDataParser(array);
        } catch (Exception e) {
            throw new IllegalStateException("could not parse __NUXT_DATA__: " + e.getMessage(), e);
        }
    }

    /**
     * Find and inflate the first raw entry that is an object containing all the
     * given keys. Used to locate the product-variant object without depending
     * on the exact shape of the surrounding page state.
     */
    public JsonNode findObjectWithKeys(String... keys) {
        for (int i = 0; i < flat.size(); i++) {
            JsonNode raw = flat.get(i);
            if (raw.isObject() && hasAllKeys(raw, keys)) {
                return resolve(i, 0);
            }
        }
        return null;
    }

    private static boolean hasAllKeys(JsonNode node, String... keys) {
        for (String key : keys) {
            if (!node.has(key)) {
                return false;
            }
        }
        return true;
    }

    private JsonNode resolve(int index, int depth) {
        if (depth > MAX_DEPTH) {
            return NullNode.getInstance();
        }
        if (index < 0 || index >= flat.size()) {
            // devalue encodes undefined/NaN/holes as negative indices
            return NullNode.getInstance();
        }
        JsonNode cached = memo.get(index);
        if (cached != null) {
            return cached;
        }
        JsonNode raw = flat.get(index);
        if (raw.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            memo.put(index, out); // insert before recursing to break cycles
            raw.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                out.set(entry.getKey(), value.isInt() ? resolve(value.intValue(), depth + 1) : value);
            });
            return out;
        }
        if (raw.isArray()) {
            ArrayNode rawArray = (ArrayNode) raw;
            if (rawArray.size() == 2 && rawArray.get(0).isTextual()
                    && WRAPPER_MARKERS.contains(rawArray.get(0).asText()) && rawArray.get(1).isInt()) {
                JsonNode unwrapped = resolve(rawArray.get(1).intValue(), depth + 1);
                memo.put(index, unwrapped);
                return unwrapped;
            }
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            memo.put(index, out);
            for (JsonNode element : rawArray) {
                out.add(element.isInt() ? resolve(element.intValue(), depth + 1) : element);
            }
            return out;
        }
        memo.put(index, raw);
        return raw;
    }
}

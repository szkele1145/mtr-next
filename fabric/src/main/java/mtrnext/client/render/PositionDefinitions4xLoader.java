package mtrnext.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mtrnext.MtrNext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [0.35.3] 4.0 车包 {@code definition_*.json}（positionDefinitions）解析。
 * <p>
 * 上游语义（{@code ModelPropertiesPart.java:654-656}）：
 * <pre>
 * positions.forEach(p -&gt; cb.accept(p.getX(), p.getY(), p.getZ(), false));
 * positionsFlipped.forEach(p -&gt; cb.accept(-p.getX(), p.getY(), p.getZ(), true));
 * </pre>
 * 即 {@code positions} 与 {@code positionsFlipped} <b>都要渲染</b>（不是二选一）：
 * 前者 {@code flipped=false}，后者 X 取反且 {@code flipped=true}。
 * {@code flipped} 随后传入门曲线（上游 L241-242 的
 * {@code getDoorAnimationX(doorXMultiplier, partDetails.flipped, time)}），
 * 经 {@code copySign} 使门位移方向反号。
 * <p>
 * 实测 ERIDU-DG1000A（{@code properties_1fa88c30.json}）：
 * {@code men2} 有两条 part 记录——一条的位定义只含 {@code positionsFlipped}、
 * 另一条只含 {@code positions}；{@code men3} 同理（倍率 −11 vs +11）。
 * 即同一 bbmodel 门几何要在车体两侧各渲染一次，一次 flipped=false、
 * 一次 flipped=true。只取其中一条会让另一侧门位移方向反掉。
 * <p>
 * 另有 43/189 个位定义的 {@code positions} 为空（部件只存在于翻转位）。
 */
public final class PositionDefinitions4xLoader {

	/** 已解析缓存: definition 资源路径 → (位定义名 → 位定义) */
	private static final Map<String, Map<String, PositionGroup>> CACHE = new ConcurrentHashMap<>();

	private PositionDefinitions4xLoader() {
	}

	public static void invalidate() {
		CACHE.clear();
	}

	/**
	 * 载入一个 {@code definition_*.json}。
	 *
	 * @return 位定义名 → 位置组；资源缺失/畸形返回空表（调用方按"无位定义"退化）
	 */
	public static Map<String, PositionGroup> load(ResourceManager manager, String definitionResource) {
		if (definitionResource == null || definitionResource.isEmpty()) {
			return Collections.emptyMap();
		}
		return CACHE.computeIfAbsent(definitionResource, k -> parse(manager, k));
	}

	private static Map<String, PositionGroup> parse(ResourceManager manager, String resource) {
		final Map<String, PositionGroup> out = new java.util.LinkedHashMap<>();
		try {
			final ResourceLocation id = new ResourceLocation(resource);
			if (!manager.getResource(id).isPresent()) {
				return Collections.emptyMap();
			}
			try (InputStreamReader r = new InputStreamReader(
					manager.getResource(id).get().open(), StandardCharsets.UTF_8)) {
				return parseRoot(JsonParser.parseReader(r).getAsJsonObject());
			}
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("positionDefinitions parse failed: {}", resource);
			return Collections.emptyMap();
		}
	}

	/** 离线自测入口：直接喂已解析的 JSON 根 */
	public static Map<String, PositionGroup> parseRoot(JsonObject root) {
		final Map<String, PositionGroup> out = new java.util.LinkedHashMap<>();
		if (root == null || !root.has("positionDefinitions")
				|| !root.get("positionDefinitions").isJsonArray()) {
			return out;
		}
		for (final JsonElement el : root.getAsJsonArray("positionDefinitions")) {
			if (el == null || !el.isJsonObject()) {
				continue;
			}
			final JsonObject def = el.getAsJsonObject();
			final String name = def.has("name") && def.get("name").isJsonPrimitive()
					? def.get("name").getAsString() : "";
			if (name.isEmpty()) {
				continue;
			}
			out.put(name, new PositionGroup(readPositions(def, "positions"),
					readPositions(def, "positionsFlipped")));
		}
		return out;
	}

	private static List<float[]> readPositions(JsonObject def, String key) {
		final List<float[]> list = new ArrayList<>();
		if (!def.has(key) || !def.get(key).isJsonArray()) {
			return list;
		}
		for (final JsonElement el : def.getAsJsonArray(key)) {
			if (el == null || !el.isJsonObject()) {
				continue;
			}
			final JsonObject p = el.getAsJsonObject();
			list.add(new float[]{(float) num(p, "x"), (float) num(p, "y"), (float) num(p, "z")});
		}
		return list;
	}

	private static double num(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : 0.0;
	}

	/** 单个位定义：正位 + 翻转位（单位与上游一致 = 像素） */
	public static final class PositionGroup {
		public final List<float[]> positions;
		public final List<float[]> positionsFlipped;

		public PositionGroup(List<float[]> positions, List<float[]> positionsFlipped) {
			this.positions = positions;
			this.positionsFlipped = positionsFlipped;
		}

		/** 是否只有翻转位（部件只存在于镜像侧） */
		public boolean flippedOnly() {
			return positions.isEmpty() && !positionsFlipped.isEmpty();
		}

		/** 是否有正位 */
		public boolean hasNormal() {
			return !positions.isEmpty();
		}
	}
}

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [0.29 重构] bbmodel 解析器——自定义盒数据结构（per-face UV 正确版）。
 * <p>
 * 旧实现把 bbmodel 的每面 UV 塞进 MC CubeListBuilder 的整盒 texOffs 模型，
 * 生成 6 个重叠盒且 UV 错位（docs/bbox-uv-flaw.md 完整分析）。正确方案：
 * 保留 bbmodel 的原生语义——一个盒、六面、每面独立 UV——由渲染器逐面绘制。
 * <p>
 * 产物 {@link BbBox} 列表直接给 BbModelTrainRenderer 的 quad 绘制路径。
 * 门部件（name 含 door）单独标记，渲染时按门参数整体偏移。
 */
public final class BbModelLoader {

	/** 已解析缓存：模型资源路径 → 盒列表 */
	private static final Map<String, List<BbBox>> CACHE = new ConcurrentHashMap<>();

	private BbModelLoader() {
	}

	public static void invalidate() {
		CACHE.clear();
	}

	/** 解析并缓存 bbmodel 的盒列表（null=资源不存在/解析失败） */
	public static List<BbBox> load(ResourceManager manager, String modelResource) {
		return CACHE.computeIfAbsent(modelResource, k -> parse(manager, k));
	}

	private static List<BbBox> parse(ResourceManager manager, String modelResource) {
		try {
			final ResourceLocation id = new ResourceLocation(modelResource);
			if (!manager.getResource(id).isPresent()) {
				MtrNext.LOGGER.warn("bbmodel not found: {}", modelResource);
				return null;
			}
			try (InputStreamReader r = new InputStreamReader(
					manager.getResource(id).get().open(), StandardCharsets.UTF_8)) {
				final JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
				return buildBoxes(root);
			}
		} catch (final Throwable t) {
			MtrNext.LOGGER.error("Failed to parse bbmodel {}", modelResource, t);
			return null;
		}
	}

	private static List<BbBox> buildBoxes(JsonObject root) {
		final List<BbBox> boxes = new ArrayList<>();
		if (!root.has("elements") || !root.get("elements").isJsonArray()) {
			return boxes;
		}
		for (final JsonElement el : root.getAsJsonArray("elements")) {
			final JsonObject element = el.getAsJsonObject();
			final BbBox box = buildBox(element);
			if (box != null) {
				boxes.add(box);
			}
		}
		return boxes;
	}

	private static BbBox buildBox(JsonObject element) {
		if (!element.has("from") || !element.has("to")) {
			return null;
		}
		final JsonArray from = element.getAsJsonArray("from");
		final JsonArray to = element.getAsJsonArray("to");
		final String name = str(element, "name", "");
		final boolean isDoor = name.toLowerCase(java.util.Locale.ROOT).contains("door");

		final BbBox box = new BbBox(isDoor);
		box.x1 = f(from, 0);
		box.y1 = f(from, 1);
		box.z1 = f(from, 2);
		box.x2 = f(to, 0);
		box.y2 = f(to, 1);
		box.z2 = f(to, 2);

		// rotation + origin（BlockBench 三轴欧拉）
		if (element.has("rotation")) {
			final JsonArray rot = element.getAsJsonArray("rotation");
			box.rotX = (float) Math.toRadians(f(rot, 0));
			box.rotY = (float) Math.toRadians(f(rot, 1));
			box.rotZ = (float) Math.toRadians(f(rot, 2));
		}
		if (element.has("origin")) {
			final JsonArray origin = element.getAsJsonArray("origin");
			box.originX = f(origin, 0);
			box.originY = f(origin, 1);
			box.originZ = f(origin, 2);
		}

		// 六面 UV（每面独立：u1,v1,u2,v2；缺面=不绘制）
		if (element.has("faces") && element.get("faces").isJsonObject()) {
			final JsonObject faces = element.getAsJsonObject("faces");
			box.uvDown = readUv(faces, "down");
			box.uvUp = readUv(faces, "up");
			box.uvNorth = readUv(faces, "north");
			box.uvSouth = readUv(faces, "south");
			box.uvEast = readUv(faces, "east");
			box.uvWest = readUv(faces, "west");
		}
		return box;
	}

	private static float[] readUv(JsonObject faces, String face) {
		if (!faces.has(face)) {
			return null;
		}
		final JsonObject f = faces.getAsJsonObject(face);
		if (!f.has("uv")) {
			return null;
		}
		final JsonArray uv = f.getAsJsonArray("uv");
		return new float[]{f(uv, 0), f(uv, 1), f(uv, 2), f(uv, 3)};
	}

	private static float f(JsonArray a, int i) {
		return a.size() > i ? a.get(i).getAsFloat() : 0;
	}

	private static String str(JsonObject o, String key, String def) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
	}

	/** 兼容旧调用点的退化接口：不再做 MC bake */
	@Deprecated
	public static Object bake(ResourceManager manager, String modelResource) {
		return load(manager, modelResource);
	}

	/**
	 * bbmodel 盒（原生语义：一盒六面、每面独立 UV）。
	 * 渲染器按面绘制，门盒整体偏移。
	 */
	public static final class BbBox {
		public float x1, y1, z1, x2, y2, z2;
		public float rotX, rotY, rotZ;
		public float originX, originY, originZ;
		public final boolean isDoor;
		public float[] uvDown, uvUp, uvNorth, uvSouth, uvEast, uvWest;

		BbBox(boolean isDoor) {
			this.isDoor = isDoor;
		}
	}
}

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

	/** 解析并缓存 bbmodel 的盒列表（null=资源不存在/解析失败；空表=合法但无盒） */
	public static List<BbBox> load(ResourceManager manager, String modelResource) {
		if (modelResource == null || modelResource.isEmpty()) {
			// [0.34.3] props-only 层：实测 ERIDU-DG1000A 的 models[0] 只有
			// properties（承载整车 gangway/barrier），modelResource 为空串。
			// 此前会一路走到 new ResourceLocation("") 抛异常 → LOGGER.error
			// 每帧刷一条，且 computeIfAbsent 对 null 不缓存 → 每帧重演。
			// 空串短路成空盒表，渲染循环的 isEmpty() 分支自然跳过。
			return java.util.Collections.emptyList();
		}
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
		// [0.34.3] outliner 解析：element uuid → 祖先分组名链。
		// 实测 eridudanguei：element.name 无门信息（全是 cube），门归属在
		// outliner 组名（door/men2/men3...），properties parts[].names 匹配
		// 的正是这些组名。children 是混合数组（uuid 字符串 + 子组对象）。
		final Map<String, List<String>> groupPaths = new java.util.HashMap<>();
		if (root.has("outliner") && root.get("outliner").isJsonArray()) {
			walkOutliner(root.getAsJsonArray("outliner"), new java.util.ArrayDeque<>(), groupPaths);
		}
		for (final JsonElement el : root.getAsJsonArray("elements")) {
			final JsonObject element = el.getAsJsonObject();
			final BbBox box = buildBox(element);
			if (box != null) {
				final List<String> path = groupPaths.get(str(element, "uuid", ""));
				box.groupPath = path != null ? path : java.util.List.of();
				boxes.add(box);
			}
		}
		return boxes;
	}

	/**
	 * [0.34.3] 递归 outliner：为每个 element uuid 记录从根到叶的祖先组名链。
	 * children 混合数组：字符串 = element uuid 引用；对象 = 子分组（可再嵌套）。
	 */
	private static void walkOutliner(com.google.gson.JsonArray arr, java.util.Deque<String> path,
			Map<String, List<String>> out) {
		for (final JsonElement el : arr) {
			if (el == null) {
				continue;
			}
			if (el.isJsonPrimitive()) {
				out.put(el.getAsString(), List.copyOf(path));
			} else if (el.isJsonObject()) {
				final JsonObject group = el.getAsJsonObject();
				path.addLast(str(group, "name", ""));
				if (group.has("children") && group.get("children").isJsonArray()) {
					walkOutliner(group.getAsJsonArray("children"), path, out);
				}
				path.removeLast();
			}
		}
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
	 * [0.34.3] 组名归属门判定（数据面，渲染端暂不消费）。
	 * <p>
	 * 对 4.0 车包：parts[].names 匹配 outliner 组名；带非零门倍率的部件
	 * 其组名下的所有盒视为门部件。falls back 到 element.name contains
	 * "door"（3.x / YMTR 导出模型的旧约定）。
	 */
	public static boolean isDoorByGroup(BbBox box, java.util.List<Properties4xLoader.PartInfo> parts) {
		if (box == null) {
			return false;
		}
		if (parts != null && !box.groupPath.isEmpty()) {
			for (Properties4xLoader.PartInfo p : parts) {
				if (p.doorXMultiplier != 0.0F || p.doorZMultiplier != 0.0F) {
					for (String g : box.groupPath) {
						if (p.names.contains(g)) {
							return true;
						}
					}
				}
			}
		}
		return box.isDoor;
	}

	/**
	 * bbmodel 盒（原生语义：一盒六面、每面独立 UV）。
	 * 渲染器按面绘制，门盒整体偏移。
	 * <p>
	 * [0.34.3] isDoor 的现行判定（element.name 含 "door"）对 4.0 车包
	 * 静默失效——门归属在 properties_*.json 的 parts[].names（匹配
	 * outliner 分组名），见 docs/door-animation-audit.md。本字段保留，
	 * 待 parts[] 解析接入后由组名归属取代。
	 */
	public static final class BbBox {
		public float x1, y1, z1, x2, y2, z2;
		public float rotX, rotY, rotZ;
		public float originX, originY, originZ;
		public final boolean isDoor;
		/** [0.34.3] element 所在的 outliner 祖先组名链（根→叶；无组 = 空表） */
		public List<String> groupPath = java.util.List.of();
		public float[] uvDown, uvUp, uvNorth, uvSouth, uvEast, uvWest;

		BbBox(boolean isDoor) {
			this.isDoor = isDoor;
		}
	}
}

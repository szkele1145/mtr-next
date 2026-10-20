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

	/**
	 * [0.35] 按"模型+属性"双键缓存已标注门参数的层。
	 * <p>
	 * 同一 bbmodel 可能被多个 vehicle/层以不同 properties_*.json 引用，
	 * 门倍率/动画型归属 parts[]（properties）而非模型本身——单键会串。
	 * 渲染器每帧调本方法，匹配结果全走缓存，只在首帧做 O(盒数×parts数) 扫描。
	 */
	private static final Map<String, List<BbBox>> ANNOTATED = new ConcurrentHashMap<>();

	public static List<BbBox> loadAnnotated(ResourceManager manager, String modelResource, String propertiesResource) {
		return loadAnnotated(manager, modelResource, propertiesResource, "");
	}

	/**
	 * [0.35.3] 载入并标注（带位定义 → 镜像展开）。
	 *
	 * @param positionDefinitionsResource definition_*.json 资源路径（可空）
	 */
	public static List<BbBox> loadAnnotated(ResourceManager manager, String modelResource,
			String propertiesResource, String positionDefinitionsResource) {
		if (modelResource == null || modelResource.isEmpty()) {
			return java.util.Collections.emptyList();
		}
		return ANNOTATED.computeIfAbsent(modelResource + "|" + propertiesResource + "|" + positionDefinitionsResource,
				k -> annotate(load(manager, modelResource),
						propertiesResource == null || propertiesResource.isEmpty()
								? null : Properties4xLoader.load(manager, propertiesResource).parts,
						PositionDefinitions4xLoader.load(manager, positionDefinitionsResource)));
	}

	/**
	 * [0.35] 把 parts[] 的门参数（倍率/动画型/侧别）写入盒字段；非门盒字段清零
	 */
	private static List<BbBox> annotate(List<BbBox> boxes, List<Properties4xLoader.PartInfo> parts) {
		return annotate(boxes, parts, null);
	}

	/**
	 * [0.35.3] 标注 + 镜像展开。
	 *
	 * @param posDefs 位定义表（definition_*.json：名字 → 位置组）；可为 null
	 */
	private static List<BbBox> annotate(List<BbBox> boxes, List<Properties4xLoader.PartInfo> parts,
			Map<String, PositionDefinitions4xLoader.PositionGroup> posDefs) {
		if (boxes == null) {
			return null;
		}
		// [0.35.3] 顶层组 → 是否有 flipped 副本。
		// 上游镜像粒度 = 整个 modelPart（顶层 outliner 组），故按顶层组判定。
		final java.util.Set<String> mirroredGroups = new java.util.HashSet<>();
		if (posDefs != null && parts != null) {
			for (final Properties4xLoader.PartInfo p : parts) {
				for (final String defName : p.positionDefinitionNames) {
					final PositionDefinitions4xLoader.PositionGroup g = posDefs.get(defName);
					if (g != null && !g.positionsFlipped.isEmpty()) {
						for (final String n : p.names) {
							mirroredGroups.add(n);
						}
					}
				}
			}
		}
		for (final BbBox box : boxes) {
			box.doorXMult = 0;
			box.doorZMult = 0;
			box.doorAnim = null;
			box.doorSide = 0;
			box.mirrorNeeded = false;
			box.doorFlipped = false;
			if (parts == null || box.groupPath.isEmpty()) {
				continue;
			}
			// 顶层组 = groupPath 首元素（上游 nameToPart 的键即顶层组名）
			final String topGroup = box.groupPath.get(0);
			// [0.35.3] 镜像双条件：①几何只建单侧（singleSidedGroup，对称组如
			// end/hand 上游镜像会与对面正本重叠，跳过）；②组引用了含翻转位
			// 的位定义（上游 iteratePositions 只对 positionsFlipped 非空的
			// 定义画镜像副本——FLIPPED 槽即镜像）。
			box.mirrorNeeded = box.singleSidedGroup && mirroredGroups.contains(topGroup);
			for (final Properties4xLoader.PartInfo p : parts) {
				if (p.doorXMultiplier == 0.0F && p.doorZMultiplier == 0.0F) {
					continue; // 非门部件（上游 isDoor() 同判定）
				}
				boolean matched = false;
				for (final String g : box.groupPath) {
					if (p.names.contains(g)) {
						matched = true;
						break;
					}
				}
				if (!matched) {
					continue;
				}
				box.doorXMult = p.doorXMultiplier;
				box.doorZMult = p.doorZMultiplier;
				box.doorAnim = DoorAnimation4x.parse(p.doorAnimationType);
				// [0.35.3] 门位移方向：若该 part 的位定义只含 positionsFlipped
				// （上游 flipped=true → copySign 反号），则门位移需反号。
				box.doorFlipped = isFlippedOnly(p, posDefs);
				// 侧别：盒质心 X（MC 格，已镜像）。ERIDU 门组质心 |cx|≈1.25 格；
				// 阈值 0.05 格内视为中置（两侧门值取大者，保险语义）。
				final float cx = (box.x1 + box.x2) / 2.0F;
				box.doorSide = cx < -0.05F ? -1 : cx > 0.05F ? 1 : 0;
				break;
			}
		}
		return boxes;
	}

	/**
	 * [0.35.3] 该 part 记录是否引用了"只含翻转位"的位定义（上游 flipped=true 槽）。
	 * <p>
	 * 上游 iteratePositions 同时遍历两个列表，flipped 位额外传 flipped=true；
	 * mtr-next 的 bbmodel 几何只有一份（+X 半边），flipped 副本由
	 * mirrorNeeded 统一绘制，故此处只作标记（本体/镜像的位移符号由
	 * drawMirrorPair 按 flipped=false/true 固定给出）。
	 */
	private static boolean isFlippedOnly(Properties4xLoader.PartInfo p,
			Map<String, PositionDefinitions4xLoader.PositionGroup> posDefs) {
		if (posDefs == null) {
			return false;
		}
		for (final String defName : p.positionDefinitionNames) {
			final PositionDefinitions4xLoader.PositionGroup g = posDefs.get(defName);
			if (g != null && !g.positionsFlipped.isEmpty()) {
				return true;
			}
		}
		return false;
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
		// [0.35] UV 归一化除数 = bbmodel 的 resolution（BB 画布尺寸）。
		// 实测 4 个模型的贴图均为画布整数倍放大（4096/512=8、4320/24=180、
		// 8640/64=135），故除 resolution 与除贴图像素尺寸数学等效，但
		// resolution 解析层即可拿到，无需加载贴图。旧实现硬编码 24
		// （3.x 方块贴图尺寸），对 bbmodel 完全错误 → 所有贴图 UV 错位。
		float resW = 16.0F;
		float resH = 16.0F;
		if (root.has("resolution") && root.get("resolution").isJsonObject()) {
			final JsonObject res = root.getAsJsonObject("resolution");
			// 实测 resolution 形如 {"width":512,"height":512}（BB 画布尺寸）
			if (res.has("width") && res.get("width").isJsonPrimitive()) {
				resW = res.get("width").getAsFloat();
			}
			if (res.has("height") && res.get("height").isJsonPrimitive()) {
				resH = res.get("height").getAsFloat();
			}
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
				box.topGroup = box.groupPath.isEmpty() ? "" : box.groupPath.get(0);
				box.uvScaleU = resW <= 0 ? 1.0F : 1.0F / resW;
				box.uvScaleV = resH <= 0 ? 1.0F : 1.0F / resH;
				boxes.add(box);
			}
		}
		// [0.35.3] 顶层组的 X 侧分布 → 判定该组是否"只建了一侧"。
		// 上游镜像粒度 = 整个 modelPart（顶层 outliner 组），但上游是把
		// positions 与 positionsFlipped 各渲染一次；几何本就左右对称的组
		// （如 ERIDU 的 end/hand）两侧都有盒，再镜像会重叠。故只对
		// "单侧组"补镜像——既补齐缺失的另一侧，又不制造重叠。
		final Map<String, int[]> sideStats = new java.util.HashMap<>(); // 组 → {右,左,中}
		for (final BbBox box : boxes) {
			if (box.topGroup.isEmpty()) {
				continue;
			}
			final int[] s = sideStats.computeIfAbsent(box.topGroup, k -> new int[3]);
			final float cx = (box.x1 + box.x2) / 2.0F;
			if (cx > SIDE_THRESHOLD) {
				s[0]++;
			} else if (cx < -SIDE_THRESHOLD) {
				s[1]++;
			} else {
				s[2]++;
			}
		}
		for (final BbBox box : boxes) {
			if (box.topGroup.isEmpty()) {
				continue;
			}
			final int[] s = sideStats.get(box.topGroup);
			if (s == null) {
				continue;
			}
			// 单侧组：只在一侧有几何 → 另一侧靠镜像补齐
			box.singleSidedGroup = (s[0] > 0) != (s[1] > 0);
		}
		return boxes;
	}

	/** [0.35.3] 判定"单侧"的中置阈值（MC 格）——对应 bbmodel 像素 5px */
	private static final float SIDE_THRESHOLD = 5.0F / 16.0F;

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
		// [0.35] 尺度换算：16 Blockbench 像素 = 1 MC 格（上游
		// BlockbenchElement.setModelPart 的 /16 同源）。旧实现把像素值
		// 当格直接写顶点，车体放大 16 倍——0.29 起的渲染尺度 bug。
		// 统一在解析层换算，渲染层保持"格"语义，门倍率同链路。
		box.x1 = f(from, 0) / 16.0F;
		box.y1 = f(from, 1) / 16.0F;
		box.z1 = f(from, 2) / 16.0F;
		box.x2 = f(to, 0) / 16.0F;
		box.y2 = f(to, 1) / 16.0F;
		box.z2 = f(to, 2) / 16.0F;

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
	 * [0.34.3] 测试/离线回归入口：直接从 JsonObject 解析盒列表（跳过 ResourceManager）。
	 * <p>
	 * {@code load(manager, path)} 依赖 MC 资源系统无法离线测；此方法把
	 * {@code buildBoxes} 暴露给 ParserSelfTest，使 outliner → groupPath
	 * 的解析逻辑可 regression。生产代码不调用。
	 */
	public static List<BbBox> parseForTest(JsonObject root) {
		return buildBoxes(root);
	}

	/**
	 * [0.35] 测试/离线回归入口：把门参数标注到盒上（跳过 ResourceManager）。
	 * <p>
	 * {@link #annotate} 是私有的，门归属判定（组名匹配 + 倍率/动画型写入）
	 * 属纯数据逻辑，可离线回归。生产代码不调用。
	 */
	public static List<BbBox> annotateForTest(List<BbBox> boxes, List<Properties4xLoader.PartInfo> parts) {
		return annotate(boxes, parts, null);
	}

	/** [0.35.3] 离线自测入口：带位定义表（镜像展开） */
	public static List<BbBox> annotateForTest(List<BbBox> boxes, List<Properties4xLoader.PartInfo> parts,
			Map<String, PositionDefinitions4xLoader.PositionGroup> posDefs) {
		return annotate(boxes, parts, posDefs);
	}

	/**
	 * [0.35.2] 门重叠离线检测（上游 DynamicVehicleModel.testDoors 的移植）。
	 * <p>
	 * 上游语义：模拟门从 t=0 到 t=1 走 5 个时间片，若两扇门在"初始不相交、
	 * 中途相交"，说明门 X/Z 倍率配错（车包作者配置错误），上游打 WARN。
	 * 本方法为纯数据逻辑，可离线回归；供 ParserSelfTest 与将来运行时
	 * 资源重载时的告警复用。
	 *
	 * @return 重叠的门盒对描述列表（空 = 无问题）
	 */
	public static List<String> detectDoorOverlaps(List<BbBox> boxes) {
		final List<String> problems = new ArrayList<>();
		if (boxes == null) {
			return problems;
		}
		// 只看门盒；带索引便于报错定位
		final List<BbBox> doors = new ArrayList<>();
		for (final BbBox box : boxes) {
			if (box.isAnimatedDoor()) {
				doors.add(box);
			}
		}
		if (doors.size() < 2) {
			return problems;
		}
		final int slices = 5;
		for (int i = 0; i < doors.size(); i++) {
			for (int j = i + 1; j < doors.size(); j++) {
				final BbBox a = doors.get(i), b = doors.get(j);
				// 初始（t=0）不相交才检测中途相交——上游同款语义
				if (a.intersects(b, 0, 0, 0, 0)) {
					continue;
				}
				for (int k = 1; k <= slices; k++) {
					final float t = (float) k / slices;
					final float ax = a.doorAnim.getDoorAnimationX(a.doorXMult, false, t) / 16.0F;
					final float az = a.doorAnim.getDoorAnimationZ(a.doorZMult, false, t, true) / 16.0F;
					final float bx = b.doorAnim.getDoorAnimationX(b.doorXMult, false, t) / 16.0F;
					final float bz = b.doorAnim.getDoorAnimationZ(b.doorZMult, false, t, true) / 16.0F;
					if (a.intersects(b, ax, az, bx, bz)) {
						problems.add("doors overlap at t=" + t + ": group=[" + String.join("/", a.groupPath)
								+ "] vs [" + String.join("/", b.groupPath) + "]");
						break;
					}
				}
			}
		}
		return problems;
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
		/** [0.35] UV 归一化倒数（1/resolution 宽、1/resolution 高） */
		public float uvScaleU = 1.0F / 24.0F;
		public float uvScaleV = 1.0F / 24.0F;

		// ── [0.35] 门动画参数（来自 properties parts[]，单位=像素） ──
		/** 门 X 倍率（上游 doorXMultiplier，16 像素 = 1 格） */
		public float doorXMult = 0.0F;
		/** 门 Z 倍率（上游 doorZMultiplier） */
		public float doorZMult = 0.0F;
		/** 门动画曲线类型（null = 非门） */
		public DoorAnimation4x doorAnim = null;
		/** 门所在侧：-1=车体左(X 负)、+1=车体右(X 正)、0=中置/不区分 */
		public int doorSide = 0;
		/** [0.35.3] 顶层 outliner 组名（上游 nameToPart 的键，见 DynamicVehicleModel.java:41-62） */
		public String topGroup = "";
		/**
		 * [0.35.3] 该顶层组的几何是否只建了一侧（只有 +X 或只有 −X）。
		 * <p>
		 * 实测 ERIDU：{@code men2}/{@code men3}/{@code win}/{@code door} 等
		 * 只建 +X 半边（另一侧靠上游 flipped 副本补齐）；而 {@code end}/
		 * {@code hand} 等本身左右对称（两侧都有盒）——上游若也给它们画镜像
		 * 副本会与对面正本重叠，故 mtr-next 对对称组跳过镜像。
		 */
		public boolean singleSidedGroup = false;
		/**
		 * [0.35.3] 是否需要绘制镜像副本（上游 positionsFlipped 语义）。
		 * <p>
		 * 上游 {@code ModelPropertiesPart.iteratePositions}
		 * （L654-656）把 {@code positions} 与 {@code positionsFlipped} <b>都渲染</b>：
		 * 前者 flipped=false，后者 X 取反且 flipped=true。上游渲染时
		 * {@code partDetails.flipped ? Math.PI : 0} 绕 Y 轴转 180°
		 * （{@code ModelPropertiesPart.java:364}），即顶点逐点 (x,z)→(−x,−z)。
		 * <p>
		 * 实测 ERIDU 的 {@code men2}/{@code win}/{@code door} 等顶层组
		 * <b>只建了 +X 半边</b>（men2 x∈[19,20]；win R=24 L=0），
		 * 另一侧完全靠 flipped 副本补齐 → 缺它等于左半侧车门/车窗全无。
		 */
		public boolean mirrorNeeded = false;
		/** [0.35.3] 镜像副本：门位移方向需反号（上游 copySign(abs, (flipped?-1:1)*mult)） */
		public boolean doorFlipped = false;

		BbBox(boolean isDoor) {
			this.isDoor = isDoor;
		}

		/** [0.35] 是否为可动画门部件（上游 isDoor() 同判定） */
		public boolean isAnimatedDoor() {
			return doorAnim != null;
		}

		/**
		 * [0.35.2] 轴对齐盒相交判定（含边界接触）——门重叠检测用。
		 * <p>
		 * 上游 DynamicVehicleModel.testDoors 用 {@code Box.intersects} 做同款判定
		 * （上游 org.mtr.mapping.holder.Box），此处按同样语义在本地实现。
		 *
		 * @param ox X 轴偏移（格）
		 * @param oz Z 轴偏移（格）
		 */
		public boolean intersects(BbBox other, float ox, float oz, float otherOx, float otherOz) {
			final float ax1 = x1 + ox, ax2 = x2 + ox, az1 = z1 + oz, az2 = z2 + oz;
			final float bx1 = other.x1 + otherOx, bx2 = other.x2 + otherOx;
			final float bz1 = other.z1 + otherOz, bz2 = other.z2 + otherOz;
			return ax1 <= bx2 && bx1 <= ax2 && az1 <= bz2 && bz1 <= az2;
		}
	}
}

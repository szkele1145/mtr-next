package mtrnext.client.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mtrnext.MtrNext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [0.7] 4.0 车包 properties_*.json 的解析（连接面渲染属性）。
 * <p>
 * 实证（ERIDU-DG1000A 样本）：
 * <pre>
 * { "parts": [], "modelYOffset": 1.0,
 *   "gangwayInnerSide/Top/BottomResource": "...",  // 内面贴图(侧/顶/底)
 *   "gangwayOuter...Resource": "",                  // 外面贴图(可空)
 *   "gangwayWidth/Height/YOffset/ZOffset": ...,
 *   "barrier...": ... }                             // 端墙同构字段
 * </pre>
 * gangway/barrier 是<b>程序化几何</b>：宽高 + 偏移 + 四向贴图，
 * 由 BbModelTrainRenderer.renderConnection/renderBarrier 在
 * 基类给的 8 顶点框架内绘制。
 */
public final class Properties4xLoader {

	/** 已解析缓存: properties 资源路径 → 属性 */
	private static final Map<String, GangwayProperties> CACHE = new ConcurrentHashMap<>();

	private Properties4xLoader() {
	}

	public static void invalidate() {
		CACHE.clear();
	}

	public static GangwayProperties load(ResourceManager manager, String propertiesResource) {
		return CACHE.computeIfAbsent(propertiesResource, k -> parse(manager, k));
	}

	private static GangwayProperties parse(ResourceManager manager, String resource) {
		final GangwayProperties props = new GangwayProperties();
		if (resource == null || resource.isEmpty()) {
			return props;
		}
		try {
			final ResourceLocation id = new ResourceLocation(resource);
			if (!manager.getResource(id).isPresent()) {
				return props;
			}
			try (InputStreamReader r = new InputStreamReader(
					manager.getResource(id).get().open(), StandardCharsets.UTF_8)) {
				final JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
				props.modelYOffset = dbl(root, "modelYOffset", 1.0);
				props.parts = parseParts(root);

				props.gangwayInnerSide = tex(root, "gangwayInnerSideResource");
				props.gangwayInnerTop = tex(root, "gangwayInnerTopResource");
				props.gangwayInnerBottom = tex(root, "gangwayInnerBottomResource");
				props.gangwayOuterSide = tex(root, "gangwayOuterSideResource");
				props.gangwayOuterTop = tex(root, "gangwayOuterTopResource");
				props.gangwayOuterBottom = tex(root, "gangwayOuterBottomResource");
				props.gangwayWidth = (float) dbl(root, "gangwayWidth", 1.0);
				props.gangwayHeight = (float) dbl(root, "gangwayHeight", 0.75);
				props.gangwayYOffset = (float) dbl(root, "gangwayYOffset", 0.0);
				props.gangwayZOffset = (float) dbl(root, "gangwayZOffset", 0.0);

				props.barrierInnerSide = tex(root, "barrierInnerSideResource");
				props.barrierInnerTop = tex(root, "barrierInnerTopResource");
				props.barrierInnerBottom = tex(root, "barrierInnerBottomResource");
				props.barrierWidth = (float) dbl(root, "barrierWidth", 1.0);
				props.barrierHeight = (float) dbl(root, "barrierHeight", 0.75);
				props.barrierYOffset = (float) dbl(root, "barrierYOffset", 0.0);
				props.barrierZOffset = (float) dbl(root, "barrierZOffset", 0.0);
			}
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("properties4x parse failed: {}", resource);
		}
		return props;
	}

	private static double dbl(JsonObject o, String key, double def) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : def;
	}

	private static String tex(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
	}

	/** 读字符串字段（缺失或空串都回退缺省——parts 枚举字段的容错语义） */
	private static String str(JsonObject o, String key, String def) {
		final String v = tex(o, key);
		return v.isEmpty() ? def : v;
	}

	/**
	 * [0.34.3] 4.0 properties parts[] 解析（纯数据层）。
	 * <p>
	 * 实测 ERIDU 包 42 个部件带非零门倍率（docs/mtr4-pack-format.md §3）：
	 * {@code men2} doorZMultiplier=+11.0、{@code men3}=−11.0（左右门符号相反）。
	 * parts[].names 匹配 bbmodel <b>outliner 分组名</b>（非 element.name——
	 * element.name 全是 cube/cude，门信息根本不在那里）。
	 * <p>
	 * <b>只解析不消费</b>：倍率→位移的换算单位（像素/格）与左右符号约定
	 * 尚未经 MTR 4.x 上游确认（docs/door-animation-audit.md §4），渲染端
	 * 接入被有意推迟——先让数据面可回归测试。
	 * <p>
	 * public 是为离线自测（ParserSelfTest）可直接喂 JSON，无需 ResourceManager。
	 */
	public static List<PartInfo> parseParts(JsonObject root) {
		if (!root.has("parts") || !root.get("parts").isJsonArray()) {
			return java.util.Collections.emptyList();
		}
		final List<PartInfo> out = new java.util.ArrayList<>();
		for (final com.google.gson.JsonElement el : root.getAsJsonArray("parts")) {
			if (el == null || !el.isJsonObject()) {
				continue; // 畸形部件跳过，不让整个 properties 报废
			}
			final JsonObject p = el.getAsJsonObject();
			final PartInfo info = new PartInfo();
			if (p.has("names") && p.get("names").isJsonArray()) {
				for (final com.google.gson.JsonElement n : p.getAsJsonArray("names")) {
					if (n != null && n.isJsonPrimitive()) {
						info.names.add(n.getAsString());
					}
				}
			}
			info.doorXMultiplier = (float) dbl(p, "doorXMultiplier", 0.0);
			info.doorZMultiplier = (float) dbl(p, "doorZMultiplier", 0.0);
			info.doorAnimationType = str(p, "doorAnimationType", "STANDARD");
			info.condition = str(p, "condition", "NORMAL");
			info.renderStage = str(p, "renderStage", "EXTERIOR");
			info.type = str(p, "type", "NORMAL");
			// [0.35.4] 门行程时序窗口（上游 modelPropertiesPart.json schema 权威字段，
			// 上游 ModelPropertiesPart.java:342-345 消费）：
			//   shouldRender = (from==0 && until==0)
			//               || isBetween(|doorValue| * DOOR_MOVE_TIME, from, until)
			// 单位 = 毫秒；DOOR_MOVE_TIME = 3200（上游 core/data/Vehicle.java:59）。
			// 语义：部件只在门的行程落在 [from, until] 毫秒窗口内才渲染——
			// 典型用途是"门灯管只在开门最初 100ms 显示"。
			info.renderFromOpeningDoorTime = (int) dbl(p, "renderFromOpeningDoorTime", 0.0);
			info.renderUntilOpeningDoorTime = (int) dbl(p, "renderUntilOpeningDoorTime", 0.0);
			info.renderFromClosingDoorTime = (int) dbl(p, "renderFromClosingDoorTime", 0.0);
			info.renderUntilClosingDoorTime = (int) dbl(p, "renderUntilClosingDoorTime", 0.0);
			// [0.35.4] 闪烁（上游 ModelPropertiesPart.java:317-320）：
			//   flashOn = flashOnTime + flashOffTime == 0
			//           || (currentTimeMillis % (flashOnTime + flashOffTime)) > flashOffTime
			// 不闪时上游把 Y 推到 Integer.MAX_VALUE（移出视野）而非跳过绘制。
			info.flashOnTime = (int) dbl(p, "flashOnTime", 0.0);
			info.flashOffTime = (int) dbl(p, "flashOffTime", 0.0);
			// [0.35.3] 位定义名（上游 ModelPropertiesPart.positionDefinitions）：
			// 指向 definition_*.json 里的位置组，决定该部件的 flipped 归属。
			if (p.has("positionDefinitions") && p.get("positionDefinitions").isJsonArray()) {
				for (final com.google.gson.JsonElement d : p.getAsJsonArray("positionDefinitions")) {
					if (d != null && d.isJsonPrimitive()) {
						info.positionDefinitionNames.add(d.getAsString());
					}
				}
			}
			out.add(info);
		}
		return java.util.Collections.unmodifiableList(out);
	}

	/** 4.0 properties parts[] 单个部件的数据面（门倍率/条件/渲染阶段） */
	public static final class PartInfo {
		/** 匹配 outliner 分组名 */
		public final java.util.List<String> names = new java.util.ArrayList<>();
		/** [0.35.3] 位定义名（上游 positionDefinitions；决定 flipped 归属） */
		public final java.util.List<String> positionDefinitionNames = new java.util.ArrayList<>();
		public float doorXMultiplier = 0.0F;
		public float doorZMultiplier = 0.0F;
		public String doorAnimationType = "STANDARD";
		public String condition = "NORMAL";
		public String renderStage = "EXTERIOR";
		public String type = "NORMAL";
		// [0.35.4] 门行程时序窗口（毫秒；0 = 不限制）
		public int renderFromOpeningDoorTime = 0;
		public int renderUntilOpeningDoorTime = 0;
		public int renderFromClosingDoorTime = 0;
		public int renderUntilClosingDoorTime = 0;
		// [0.35.4] 闪烁周期（毫秒；两者皆 0 = 常亮）
		public int flashOnTime = 0;
		public int flashOffTime = 0;

		/**
		 * [0.35.4] 该部件在给定门状态（0=关，1=全开）下是否应渲染。
		 * <p>
		 * 上游 {@code ModelPropertiesPart.java:342-345}：
		 * <pre>
		 * if (opening) {
		 *     shouldRender = (from == 0 &amp;&amp; until == 0)
		 *                 || Utilities.isBetween(|doorValue| * DOOR_MOVE_TIME, from, until);
		 * } else {
		 *     shouldRender = (from == 0 &amp;&amp; until == 0)
		 *                 || Utilities.isBetween(|doorValue| * DOOR_MOVE_TIME, from, until);
		 * }
		 * </pre>
		 * 其中 {@code DOOR_MOVE_TIME = 3200}（上游 core/data/Vehicle.java:59），
		 * {@code isBetween(v,a,b)} = {@code v >= min(a,b) &amp;&amp; v <= max(a,b)}
		 * （上游 core/tool/Utilities.java:61-63，倒序区间也支持）。
		 * <p>
		 * <b>纯数据层判定</b>：渲染端接入前，先让语义可离线回归。
		 *
		 * @param doorValue 门开度（0..1），取绝对值
		 * @param opening   true = 开门中，false = 关门中
		 */
		public boolean shouldRenderAt(double doorValue, boolean opening) {
			final int from = opening ? renderFromOpeningDoorTime : renderFromClosingDoorTime;
			final int until = opening ? renderUntilOpeningDoorTime : renderUntilClosingDoorTime;
			if (from == 0 && until == 0) {
				return true; // 上游：无窗口 = 恒渲染
			}
			final double elapsed = Math.abs(doorValue) * DOOR_MOVE_TIME;
			return elapsed >= Math.min(from, until) && elapsed <= Math.max(from, until);
		}

		/**
		 * [0.35.4] 上游 {@code Vehicle.DOOR_MOVE_TIME}（core/data/Vehicle.java:59）。
		 * 门从全关到全开所需的毫秒数；门时序窗口与门开度都以此为尺度。
		 */
		public static final double DOOR_MOVE_TIME = 3200.0;

		/**
		 * [0.35.4] 闪烁状态（上游 ModelPropertiesPart.java:317）：
		 * <pre>
		 * flashOn = flashOnTime + flashOffTime == 0
		 *         || (currentTimeMillis % (flashOnTime + flashOffTime)) > flashOffTime
		 * </pre>
		 *
		 * @param currentTimeMillis 当前时间戳（可注入以便测试）
		 */
		public boolean flashOn(long currentTimeMillis) {
			if (flashOnTime + flashOffTime == 0) {
				return true; // 上游：无闪烁配置 = 常亮
			}
			return (currentTimeMillis % (flashOnTime + flashOffTime)) > flashOffTime;
		}
	}

	/** 连接面渲染属性（gangway/barrier 双组，程序化几何参数 + 贴图） */
	public static final class GangwayProperties {
		public double modelYOffset = 1.0;
		/** [0.34.3] parts[] 数据面（门倍率/条件/阶段；渲染端暂不消费） */
		public List<PartInfo> parts = java.util.Collections.emptyList();
		public String gangwayInnerSide = "";
		public String gangwayInnerTop = "";
		public String gangwayInnerBottom = "";
		public String gangwayOuterSide = "";
		public String gangwayOuterTop = "";
		public String gangwayOuterBottom = "";
		public float gangwayWidth = 1.0F;
		public float gangwayHeight = 0.75F;
		public float gangwayYOffset = 0.0F;
		public float gangwayZOffset = 0.0F;
		public String barrierInnerSide = "";
		public String barrierInnerTop = "";
		public String barrierInnerBottom = "";
		public float barrierWidth = 1.0F;
		public float barrierHeight = 0.75F;
		public float barrierYOffset = 0.0F;
		public float barrierZOffset = 0.0F;
	}
}

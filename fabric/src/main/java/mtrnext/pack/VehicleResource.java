package mtrnext.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mtrnext.MtrNext;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 4.0 格式车辆定义（镜像 MTR 4.x VehicleResource 的数据面，不含渲染管线）。
 * <p>
 * 字段实证自 ERIDU-DG1000A 车包（docs/mtr4-pack-format.md）：
 * <pre>
 * { "id": "...", "name": "...", "color": "D67C00", "transportMode": "TRAIN",
 *   "length": 12.0, "width": 3.0,
 *   "bogie1Position": -4.0, "bogie2Position": 4.0,
 *   "couplingPadding1": 0.0, "couplingPadding2": 0.0,
 *   "hasGangway1": false, "hasGangway2": true,      // 贯通道（风挡）
 *   "hasBarrier1": false, "hasBarrier2": true,      // 端墙挡板
 *   "models": [ { "modelResource", "textureResource",
 *                 "modelPropertiesResource", "positionDefinitionsResource",
 *                 "flipTextureV" } ],
 *   "bogie1Models": [], "bogie2Models": [],
 *   "bveSoundBaseResource": "...", "legacy*": ... }
 * </pre>
 * 连接面语义：M_F 仅 2 端可连 / M_T 双端 / M_B 仅 1 端——编组（头连头/重联）
 * 的合法性由相邻车连接面 + family 校验决定。
 */
public final class VehicleResource {

	public final String id;
	public final String name;
	public final int color;
	public final String transportMode;
	public final double length;
	public final double width;
	public final double bogie1Position;
	public final double bogie2Position;
	public final double couplingPadding1;
	public final double couplingPadding2;
	public final boolean hasGangway1;
	public final boolean hasGangway2;
	public final boolean hasBarrier1;
	public final boolean hasBarrier2;
	public final String bveSoundBaseResource;
	/** [0.28] 4.0 车包的 legacyRiderOffset（3.x 骑乘高度回退字段，ERIDU 样本=0.0） */
	public final double legacyRiderOffset;
	public final List<ModelLayer> models;
	public final List<ModelLayer> bogie1Models;
	public final List<ModelLayer> bogie2Models;

	/** 站位锚点（definition_*.json 的 positionDefinitions 解析产物，暂存原始名） */
	public final List<String> positionDefinitionNames;

	private VehicleResource(String id, String name, int color, String transportMode,
			double length, double width, double b1, double b2,
			double cp1, double cp2, boolean g1, boolean g2, boolean ba1, boolean ba2,
			String bve, double legacyRiderOffset,
			List<ModelLayer> models, List<ModelLayer> bogie1, List<ModelLayer> bogie2,
			List<String> posDefs) {
		this.id = id;
		this.name = name;
		this.color = color;
		this.transportMode = transportMode;
		this.length = length;
		this.width = width;
		this.bogie1Position = b1;
		this.bogie2Position = b2;
		this.couplingPadding1 = cp1;
		this.couplingPadding2 = cp2;
		this.hasGangway1 = g1;
		this.hasGangway2 = g2;
		this.hasBarrier1 = ba1;
		this.hasBarrier2 = ba2;
		this.bveSoundBaseResource = bve;
		this.legacyRiderOffset = legacyRiderOffset;
		this.models = models;
		this.bogie1Models = bogie1;
		this.bogie2Models = bogie2;
		this.positionDefinitionNames = posDefs;
	}

	/**
	 * [0.32] 解析单个 4.x 车辆定义。
	 * <p>
	 * {@code manager} 允许为 {@code null}：此时所有资源引用
	 * （bbmodel/texture/positionDefinitions）被跳过，只解析纯数据字段，
	 * 因此本方法可脱离客户端离线回归（见 {@code ParserSelfTest}）。
	 * <p>
	 * {@code id} 缺失或为空白时返回 {@code null}——4.x 车包里 {@code id} 是
	 * 唯一键，没有它注册表会塌成同一个 "unknown" 槽位，多辆畸形车互相覆盖，
	 * 且 GUI 里显示为不可用条目。宁可丢弃并告警，也不要静默注册坏数据。
	 * <p>
	 * 数值字段类型不合法（如 {@code "length": "abc"}）会抛出
	 * {@link NumberFormatException}，由调用方按单车隔离处理。
	 */
	public static VehicleResource fromJson(JsonObject o, ResourceManager manager) {
		if (o == null) {
			return null;
		}
		final String id = str(o, "id", "");
		if (id.isBlank()) {
			return null;
		}
		final List<ModelLayer> models = parseLayers(o, "models", manager);
		final List<ModelLayer> bogie1 = parseLayers(o, "bogie1Models", manager);
		final List<ModelLayer> bogie2 = parseLayers(o, "bogie2Models", manager);
		final List<String> posDefs = new ArrayList<>();
		for (ModelLayer layer : models) {
			posDefs.addAll(layer.positionDefinitionNames);
		}
		return new VehicleResource(
				id,
				str(o, "name", id),
				parseColor(str(o, "color", "FFFFFF")),
				str(o, "transportMode", "TRAIN"),
				num(o, "length", 12.0),
				num(o, "width", 3.0),
				num(o, "bogie1Position", -4.0),
				num(o, "bogie2Position", 4.0),
				num(o, "couplingPadding1", 0.0),
				num(o, "couplingPadding2", 0.0),
				bool(o, "hasGangway1", false),
				bool(o, "hasGangway2", false),
				bool(o, "hasBarrier1", false),
				bool(o, "hasBarrier2", false),
				str(o, "bveSoundBaseResource", ""),
				num(o, "legacyRiderOffset", 0.0),  // [0.28] 3.x 骑乘高度回退字段
				models, bogie1, bogie2, posDefs);
	}

	/**
	 * [0.32] 解析模型层数组。
	 * <p>
	 * 旧写法直接对每个元素调 {@code getAsJsonObject()}：数组里只要出现
	 * 一个非对象元素（串、数字、null），就会抛 {@code IllegalStateException}
	 * 并让<b>整辆车</b>报废。现在畸形元素按层跳过——一辆车里丢一层远比
	 * 丢整辆车合理。
	 * <p>
	 * 另修一处不一致：空/非数组路径此前返回可变 {@code ArrayList}，成功路径
	 * 返回 {@code unmodifiableList}。现在两条路径都返回不可变表。
	 */
	private static List<ModelLayer> parseLayers(JsonObject o, String key, ResourceManager manager) {
		if (!o.has(key) || !o.get(key).isJsonArray()) {
			return Collections.emptyList();
		}
		final List<ModelLayer> out = new ArrayList<>();
		for (JsonElement el : o.getAsJsonArray(key)) {
			if (el == null || !el.isJsonObject()) {
				MtrNext.LOGGER.warn("Skipping malformed model layer in \"{}\"", key);
				continue;
			}
			out.add(ModelLayer.fromJson(el.getAsJsonObject(), manager));
		}
		return Collections.unmodifiableList(out);
	}

	/**
	 * [0.32] 解析 4.x 车辆颜色（十六进制 RRGGBB，可选 {@code #} 前缀）。
	 * <p>
	 * 旧写法 {@code (int) Long.parseLong(hex, 16)} 有两个真实缺陷：
	 * <ol>
	 *   <li>不校验位宽——{@code "FFFFFFFFF"}（9 位）能解析成功再被强转 int
	 *       截断，得到与作者意图无关的颜色；</li>
	 *   <li>不裁剪空白与前缀——{@code " #FF8800 "} / {@code "#FF8800"}
	 *       在合法车包里很常见，却全部静默回退成白色。</li>
	 * </ol>
	 * 现在只接受 1~6 位十六进制，其余一律回退 {@code 0xFFFFFF}
	 * （调用方 {@code | 0xFF000000} 后为不透明白，视觉上安全）。
	 */
	private static int parseColor(String hex) {
		if (hex == null) {
			return 0xFFFFFF;
		}
		String s = hex.trim();
		if (s.startsWith("#")) {
			s = s.substring(1);
		}
		if (s.isEmpty() || s.length() > 6) {
			return 0xFFFFFF;
		}
		for (int i = 0; i < s.length(); i++) {
			final char c = s.charAt(i);
			final boolean isHex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
			if (!isHex) {
				return 0xFFFFFF;
			}
		}
		try {
			return Integer.parseInt(s, 16);
		} catch (Exception e) {
			return 0xFFFFFF;
		}
	}

	private static String str(JsonObject o, String key, String def) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
	}

	private static double num(JsonObject o, String key, double def) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : def;
	}

	private static boolean bool(JsonObject o, String key, boolean def) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : def;
	}

	/** 车体模型层（多模型层：车体/内饰/LCD 等叠放渲染） */
	public static final class ModelLayer {
		public final String modelResource;       // mtr:eridudanguei.bbmodel
		public final String textureResource;     // mtr:cheche1xa.png
		public final String propertiesResource;  // mtr:properties_1fa88c30.json
		public final boolean flipTextureV;
		/** [0.35.3] definition_*.json 原始资源路径（位定义内容，镜像展开的依据） */
		public final String positionDefinitionsResource;
		final List<String> positionDefinitionNames;
		/**
		 * [0.36.9] 模型树缓存键（{@code modelResource + "|" + propertiesResource}）——<b>预计算</b>。
		 * <p>
		 * 原先在渲染热路径里每帧每模型层做一次字符串拼接 ⇒ 每帧产生一个临时
		 * {@code String}（含 {@code StringBuilder} 内部 char[] 拷贝）。
		 * 一列车多层、每层每帧 ⇒ 每秒数千次无谓分配。
		 * <p>
		 * 键格式必须与 {@code BbModelTreeCache.tree(...)} 的算法**逐字一致** ——
		 * 两处都调 {@link #treeCacheKey(String, String)}，避免各写一份。
		 */
		public final String treeCacheKey;
		/**
		 * [0.36.9] 贴图 {@code ResourceLocation} ——<b>预计算</b>。
		 * <p>
		 * 原先每帧每模型层 {@code new ResourceLocation(...)} 一次。
		 * 贴图资源在车包生命周期内不变 ⇒ 构造时算一次即可。
		 * 解析失败（畸形资源串）时为 null，由渲染端回退到白色混凝土。
		 */
		public final net.minecraft.resources.ResourceLocation textureLocation;

		private ModelLayer(String model, String texture, String properties, boolean flipV, String posDefResource, List<String> posDefs) {
			this.modelResource = model;
			this.textureResource = texture;
			this.propertiesResource = properties;
			this.flipTextureV = flipV;
			this.positionDefinitionsResource = posDefResource;
			this.positionDefinitionNames = posDefs;
			// [0.36.9] 预计算渲染热路径用的两个常量
			this.treeCacheKey = treeCacheKey(model, properties);
			this.textureLocation = resolveLocation(texture);
		}

		/**
		 * [0.36.9] 模型树缓存键的**唯一**算法（渲染端与加载端共用）。
		 * <p>
		 * 约定：{@code modelResource} 为空 → 空串（该层不参与几何渲染）；
		 * {@code propertiesResource} 为 null → 空串。
		 */
		public static String treeCacheKey(String modelResource, String propertiesResource) {
			return (modelResource == null ? "" : modelResource) + "|"
					+ (propertiesResource == null ? "" : propertiesResource);
		}

		/** [0.36.9] 资源串 → {@code ResourceLocation}；畸形串返回 null（不抛异常） */
		private static net.minecraft.resources.ResourceLocation resolveLocation(String resource) {
			if (resource == null || resource.isEmpty()) {
				return null;
			}
			try {
				final String[] parts = resource.split(":", 2);
				return parts.length == 2
						? new net.minecraft.resources.ResourceLocation(parts[0], parts[1])
						: new net.minecraft.resources.ResourceLocation("minecraft", parts[0]);
			} catch (final Throwable ignored) {
				return null;
			}
		}

		static ModelLayer fromJson(JsonObject o, ResourceManager manager) {
			final List<String> posDefs = new ArrayList<>();
			final String defRes = str(o, "positionDefinitionsResource", "");
			if (!defRes.isEmpty() && manager != null) {
				try {
					final ResourceLocation defId = new ResourceLocation(defRes);
					if (manager.getResource(defId).isPresent()) {
						try (InputStreamReader r = new InputStreamReader(
								manager.getResource(defId).get().open(), StandardCharsets.UTF_8)) {
							final JsonObject root = new com.google.gson.Gson().fromJson(r, JsonObject.class);
							if (root != null && root.has("positionDefinitions")) {
								for (JsonElement el : root.getAsJsonArray("positionDefinitions")) {
									posDefs.add(str(el.getAsJsonObject(), "name", ""));
								}
							}
						}
					}
				} catch (Exception e) {
					MtrNext.LOGGER.debug("positionDefinitions not resolved: {}", defRes);
				}
			}
			return new ModelLayer(
					str(o, "modelResource", ""),
					str(o, "textureResource", ""),
					str(o, "modelPropertiesResource", ""),
					bool(o, "flipTextureV", true),
					defRes,
					posDefs);
		}

		private static String str(JsonObject o, String key, String def) {
			return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
		}

		private static boolean bool(JsonObject o, String key, boolean def) {
			return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : def;
		}
	}
}

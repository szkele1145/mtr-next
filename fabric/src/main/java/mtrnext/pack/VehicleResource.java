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
	public final List<ModelLayer> models;
	public final List<ModelLayer> bogie1Models;
	public final List<ModelLayer> bogie2Models;

	/** 站位锚点（definition_*.json 的 positionDefinitions 解析产物，暂存原始名） */
	public final List<String> positionDefinitionNames;

	private VehicleResource(String id, String name, int color, String transportMode,
			double length, double width, double b1, double b2,
			double cp1, double cp2, boolean g1, boolean g2, boolean ba1, boolean ba2,
			String bve, List<ModelLayer> models, List<ModelLayer> bogie1, List<ModelLayer> bogie2,
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
		this.models = models;
		this.bogie1Models = bogie1;
		this.bogie2Models = bogie2;
		this.positionDefinitionNames = posDefs;
	}

	static VehicleResource fromJson(JsonObject o, ResourceManager manager) {
		final List<ModelLayer> models = parseLayers(o, "models", manager);
		final List<ModelLayer> bogie1 = parseLayers(o, "bogie1Models", manager);
		final List<ModelLayer> bogie2 = parseLayers(o, "bogie2Models", manager);
		final List<String> posDefs = new ArrayList<>();
		for (ModelLayer layer : models) {
			posDefs.addAll(layer.positionDefinitionNames);
		}
		return new VehicleResource(
				str(o, "id", "unknown"),
				str(o, "name", str(o, "id", "unknown")),
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
				models, bogie1, bogie2, posDefs);
	}

	private static List<ModelLayer> parseLayers(JsonObject o, String key, ResourceManager manager) {
		final List<ModelLayer> out = new ArrayList<>();
		if (!o.has(key) || !o.get(key).isJsonArray()) {
			return out;
		}
		for (JsonElement el : o.getAsJsonArray(key)) {
			out.add(ModelLayer.fromJson(el.getAsJsonObject(), manager));
		}
		return Collections.unmodifiableList(out);
	}

	private static int parseColor(String hex) {
		try {
			return (int) Long.parseLong(hex, 16);
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
		final List<String> positionDefinitionNames;

		private ModelLayer(String model, String texture, String properties, boolean flipV, List<String> posDefs) {
			this.modelResource = model;
			this.textureResource = texture;
			this.propertiesResource = properties;
			this.flipTextureV = flipV;
			this.positionDefinitionNames = posDefs;
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

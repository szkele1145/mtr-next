package mtrnext.client.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mtrnext.MtrNext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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

	/** 连接面渲染属性（gangway/barrier 双组，程序化几何参数 + 贴图） */
	public static final class GangwayProperties {
		public double modelYOffset = 1.0;
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

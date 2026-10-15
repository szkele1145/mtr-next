package mtrnext.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mtrnext.MtrNext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [4.0 车包解析器] 镜像 MTR 4.x 的 mtr_custom_resources.json vehicles 段 schema。
 * <p>
 * 实证样本：ERIDU-DG1000A（见 docs/mtr4-pack-format.md）。三级结构：
 * <ol>
 *   <li>vehicles[] —— 车定义（id/几何/转向架/连接面/models 多模型层/legacy*）；</li>
 *   <li>properties_*.json —— 连接面渲染属性（程序化贯通道/端墙）；</li>
 *   <li>definition_*.json —— positionDefinitions（乘客站位锚点）。</li>
 * </ol>
 * <p>
 * <b>3.2 格式不接管</b>：仅当 mtr_custom_resources.json 存在且含非空 vehicles
 * 段时才解析为 4.0 车包；无此特征的资源包由 YMTR 的 CustomResources 原样
 * 处理（MTR Next 零侵入）。
 */
public final class VehiclePackLoader {

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	/** 4.0 车包入口路径（4.x 固定：assets/<ns>/mtr_custom_resources.json） */
	private static final ResourceLocation PACK_ENTRY = new ResourceLocation("mtr", "mtr_custom_resources.json");

	/** 已解析的 4.0 车辆（跨资源包合并，id 唯一） */
	private static final Map<String, VehicleResource> VEHICLES = new LinkedHashMap<>();

	private VehiclePackLoader() {
	}

	/** 资源重载时调用：扫描并解析全部 4.0 格式车包 */
	public static void reload(ResourceManager manager) {
		VEHICLES.clear();
		final List<Resource> entries;
		try {
			entries = new ArrayList<>(manager.getResourceStack(PACK_ENTRY));
		} catch (Exception e) {
			MtrNext.LOGGER.warn("Failed to enumerate vehicle pack entries", e);
			return;
		}
		for (Resource entry : entries) {
			// [0.32] 逐资源包隔离：一个坏包不影响其它包
			try (InputStreamReader reader = new InputStreamReader(entry.open(), StandardCharsets.UTF_8)) {
				final JsonObject root = GSON.fromJson(reader, JsonObject.class);
				final JsonArray vehicles = root != null && root.has("vehicles") ? root.getAsJsonArray("vehicles") : null;
				if (vehicles == null || vehicles.size() == 0) {
					continue; // 3.2 格式或空包 — 不接管
				}
				int loaded = 0;
				for (JsonElement el : vehicles) {
					// [0.32] 逐车隔离：单个畸形车辆定义只跳过它自己。
					// 旧写法把整个 for 放在同一个 try 内，一辆坏车会让同包后续
					// 所有车辆一起丢失，且只在日志留一行 error——车包作者很难定位。
					try {
						if (!el.isJsonObject()) {
							MtrNext.LOGGER.warn("Skipping non-object vehicle entry in {}", entry.sourcePackId());
							continue;
						}
						final VehicleResource res = VehicleResource.fromJson(el.getAsJsonObject(), manager);
						if (res == null) {
							// id 缺失/空白：注册表用它作唯一键，静默注册会塌成同一槽位
							MtrNext.LOGGER.warn("Skipping vehicle without a usable \"id\" in {}", entry.sourcePackId());
							continue;
						}
						VEHICLES.put(res.id, res);
						loaded++;
					} catch (Exception e) {
						MtrNext.LOGGER.warn("Skipping malformed vehicle in {}: {}", entry.sourcePackId(), e.toString());
					}
				}
				MtrNext.LOGGER.info("Loaded 4.0-format vehicle pack {}: {}/{} vehicles",
						entry.sourcePackId(), loaded, vehicles.size());
			} catch (Exception e) {
				MtrNext.LOGGER.error("Failed to parse vehicle pack {}", entry.sourcePackId(), e);
			}
		}
		MtrNext.LOGGER.info("MTR-Next vehicle registry: {} vehicles from 4.0 packs", VEHICLES.size());
	}

	public static Map<String, VehicleResource> getVehicles() {
		return Collections.unmodifiableMap(VEHICLES);
	}

	public static VehicleResource getVehicle(String id) {
		return VEHICLES.get(id);
	}

	public static boolean has4xVehicles() {
		return !VEHICLES.isEmpty();
	}
}

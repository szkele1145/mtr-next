package mtrnext.formation;

import mtr.client.TrainClientRegistry;
import mtr.client.TrainProperties;
import mtrnext.MtrNext;
import mtrnext.pack.VehiclePackLoader;
import mtrnext.pack.VehicleResource;

/**
 * [0.5] 4.0 车型 → YMTR 车型注册表的精确桥接。
 * <p>
 * javap 实证（mtr.client.TrainClientRegistry）：
 * <ul>
 *   <li>{@code REGISTRY: Map<String, TrainProperties>}（String = customResourcesType 车型 id）；</li>
 *   <li>{@code register(String, TrainProperties)} 公开重载——可直接注册自定义车型；</li>
 *   <li>{@code getTrainProperties(String)} 是发车/渲染的解析入口；</li>
 *   <li>{@code reset()} 资源重载时清表（3.x 内建车型重灌）。</li>
 * </ul>
 * <p>
 * <b>精确映射策略</b>：4.0 车辆的资源重载时（CustomResourcesReloadMixin 已挂钩），
 * 把每个 4.0 VehicleResource 转成 3.x {@link TrainProperties}（baseTrainType 用
 * family 名 + 渲染器指向我们自己的 bbmodel 渲染层），经 TrainClientRegistry.register
 * 注册——Siding.baseTrainType 写 family 名后，YMTR 发车/渲染就能解析到真车型，
 * 不再是无效字符串。
 * <p>
 * 渲染器：0.5 阶段先复用 3.x 的默认模型渲染（baseTrainType 映射不到时 YMTR
 * 的 getTrainProperties 返回 blank），0.6 换 bbmodel 渲染器接管。
 */
public final class TrainTypeBridge {

	private TrainTypeBridge() {
	}

	/**
	 * 把全部 4.0 车辆注册进 YMTR 的 TrainClientRegistry（资源重载时调用）。
	 * <p>
	 * [0.6] 完整构造 TrainProperties：12 参构造器注入
	 * bbmodel 渲染器（{@link mtrnext.client.render.BbModelTrainRenderer}），
	 * 车辆外观与几何全部来自 4.0 车包。
	 *
	 * @return 注册的车型数
	 */
	public static int registerAll4xVehicles() {
		int count = 0;
		try {
			for (final java.util.Map.Entry<String, VehicleResource> e : VehiclePackLoader.getVehicles().entrySet()) {
				final VehicleResource res = e.getValue();
				final String trainId = familyOf(res.id);
				final mtr.client.TrainProperties props = new mtr.client.TrainProperties(
						trainId,
						net.minecraft.network.chat.Component.literal(res.name == null || res.name.isEmpty() ? trainId : res.name),
						res.id,                       // description 先用全 id（4.0 车包无独立 description 字段）
						"",                          // wikipediaArticle
						res.color,
						(float) res.legacyRiderOffset, // [0.28] 4.0 车包的 3.x 骑乘高度回退字段
						0.0F,                        // riderOffsetDismounting
						(float) res.bogie1Position,  // bogiePosition
						res.hasGangway1 && res.hasGangway2 ? false : false, // isJacobsBogie（4.0 无对应概念）
						res.hasGangway1 || res.hasGangway2,                // hasGangwayConnection
						new mtrnext.client.render.BbModelTrainRenderer(res.id),
						res.bveSoundBaseResource.isEmpty()
								? new mtrnext.sound.BlankTrainSound()
								: mtrnext.sound.BveTrainSound.create(res.bveSoundBaseResource));
				TrainClientRegistry.register(trainId, props);
				count++;
			}
			if (count > 0) {
				MtrNext.LOGGER.info("Registered {} 4.0-format train types into YMTR registry", count);
			}
		} catch (final Throwable t) {
			MtrNext.LOGGER.error("Failed to register 4.0 train types", t);
		}
		return count;
	}

	private static String familyOf(String id) {
		return id.contains("(") ? id.substring(0, id.indexOf('(')) : id;
	}
}

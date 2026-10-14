package mtrnext.formation;

import mtrnext.MtrNext;
import mtrnext.pack.VehiclePackLoader;
import mtrnext.pack.VehicleResource;

/**
 * [0.4] 编组 → YMTR 发车系统的桥接。
 * <p>
 * javap 实证（mtr.data.Siding）：3.x 的车辆配置是
 * {@code baseTrainType: String} + {@code trainCars: int}——
 * <b>车型 + 数量</b>的简化模型，没有逐车序列/方向。
 * <p>
 * 4.0 编组（逐车 id + 方向）映射到该模型的最优策略：
 * <ul>
 *   <li><b>baseTrainType</b> = 编组中占比最多的 family 前缀对应的
 *       3.x TrainType——4.0 车包的 id 需映射为 3.x 车型（见下方映射器）；</li>
 *   <li><b>trainCars</b> = 编组车数；</li>
 *   <li>逐车序列/方向由 MTR Next 自己的渲染层接管
 *       （4.0 车包在 3.x 里渲染，不走 3.x 的重复车型模型）。</li>
 * </ul>
 * <p>
 * <b>车型映射</b>：4.0 车包的 legacy* 字段自带 3.x 回退线索；
 * 若包内无 3.x 等价车型，则退化为 3.x 的默认 TRAIN 车型
 * （编组的"数量/调度"语义仍正确，车体渲染由我们接管）。
 */
public final class FormationSidingBridge {

	private FormationSidingBridge() {
	}

	/**
	 * 把编组应用到 Siding（3.x 车辆配置面）。
	 * <p>
	 * 经反射写入 Siding 的 baseTrainType/trainCars 私有字段
	 * （3.x 无 setter——javadoc 记录字段名，反射路径与 SidingScreen 的
	 * NBT 序列化键一致：KEY_BASE_TRAIN_TYPE = "baseTrainType"）。
	 *
	 * @param siding YMTR Siding 实例
	 * @param formation 编组
	 * @return 成功应用的车数（0=编组空或全部无法映射）
	 */
	public static int applyToSiding(Object siding, Formation formation) {
		try {
			if (formation == null || formation.cars.isEmpty()) {
				return 0;
			}
			// 1) 取编组众数 family 的 4.0 车型
			String dominantId = null;
			int dominantCount = 0;
			final java.util.Map<String, Integer> counts = new java.util.HashMap<>();
			for (final Formation.Car car : formation.cars) {
				final String family = familyOf(car.vehicleId);
				final int c = counts.merge(family, 1, Integer::sum);
				if (c > dominantCount) {
					dominantCount = c;
					dominantId = car.vehicleId;
				}
			}
			final VehicleResource res = VehiclePackLoader.getVehicle(dominantId);
			if (res == null) {
				return 0;
			}

			// 2) 反射写 Siding 的 baseTrainType + trainCars
			final java.lang.reflect.Field typeField = siding.getClass().getSuperclass().getDeclaredField("baseTrainType");
			typeField.setAccessible(true);
			// 4.0 id → 3.x 车型名（本版策略: 截取 family 作为车型名候选，
			// 3.x 找不到时用空串=Y MTR 的默认回退）
			typeField.set(siding, toTrainType(res));

			final java.lang.reflect.Field carsField = siding.getClass().getSuperclass().getDeclaredField("trainCars");
			carsField.setAccessible(true);
			carsField.setInt(siding, formation.cars.size());

			MtrNext.LOGGER.info("Formation applied to siding: {} cars, base type from {}",
					formation.cars.size(), dominantId);
			return formation.cars.size();
		} catch (final Throwable t) {
			MtrNext.LOGGER.error("Failed to apply formation to siding", t);
			return 0;
		}
	}

	private static String familyOf(String id) {
		return id.contains("(") ? id.substring(0, id.indexOf('(')) : id;
	}

	/** 4.0 车型 → 3.x TrainType 名（候选映射；未命中时给 YMTR 的默认空回退） */
	private static String toTrainType(VehicleResource res) {
		// 4.0 车包的 id 如 ERIDU-DG1000A(M_F) → family ERIDU-DG1000A
		// 3.x 车型命名惯例是全大写族名；family 即最接近的 3.x 车型名。
		return familyOf(res.id);
	}
}

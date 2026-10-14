package mtrnext.formation;

import mtrnext.pack.VehicleResource;

import java.util.ArrayList;
import java.util.List;

/**
 * 编组（formation/consist）：一列车的车辆序列 + 连接面约束。
 * <p>
 * 语义镜像 MTR 4.x 的 Siding.selectedVehicleCars（ObjectArrayList<VehicleCar>）
 * + VehicleSelectorScreen 的 canAddCar 校验：
 * <ul>
 *   <li><b>头连头</b>——车 A 的 2 端接车 B 的 2 端（方向翻转编组），
 *       合法性由相邻连接面互补判定；</li>
 *   <li><b>重联</b>——同 family（同 id 前缀）车相邻；</li>
 *   <li>编组总长 = Σ(length_i) + Σ(couplingPadding 补偿)。</li>
 * </ul>
 * 存储挂到 Siding（附加），持久化经 sidecar（不侵入 YMTR messagepack）。
 */
public final class Formation {

	/** 编组单元：车辆 id + 方向（头连头的几何基础） */
	public static final class Car {
		public final String vehicleId;
		/** true = 车体朝向编组前进方向（1 端在前）；false = 翻转（2 端在前） */
		public final boolean forward;

		public Car(String vehicleId, boolean forward) {
			this.vehicleId = vehicleId;
			this.forward = forward;
		}

		public double getLength(VehicleResource res) {
			return res == null ? 12.0 : res.length;
		}
	}

	public final List<Car> cars = new ArrayList<>();

	/**
	 * 校验能否在指定位置插入（镜像 4.x canAddCar 的连接面规则）：
	 * 相邻车的连接面必须"一真一真"（gangway/barrier 任一为真即算可连端）。
	 *
	 * @param index 插入位置（0..size）
	 * @param res   待插车辆
	 * @param forward 待插车辆方向
	 */
	public boolean canAddCar(int index, VehicleResource res, boolean forward) {
		if (res == null) {
			return false;
		}
		// 前邻：index-1 车的"后端"要与待插车的"前端"相接
		if (index > 0 && index <= cars.size()) {
			final Car prev = cars.get(index - 1);
			final VehicleResource prevRes = res0(prev.vehicleId);
			final boolean prevRearConnectable = prev.forward
					? (prevRes != null && (prevRes.hasGangway2 || prevRes.hasBarrier2))
					: (prevRes != null && (prevRes.hasGangway1 || prevRes.hasBarrier1));
			final boolean newFrontConnectable = forward
					? (res.hasGangway1 || res.hasBarrier1)
					: (res.hasGangway2 || res.hasBarrier2);
			if (prevRes != null && !prevRearConnectable && !newFrontConnectable) {
				// 两端都无连接面：仅允许同为 family 的重联
				if (!isSameFamily(prev.vehicleId, res.id)) {
					return false;
				}
			}
		}
		return true;
	}

	private VehicleResource res0(String id) {
		return mtrnext.pack.VehiclePackLoader.getVehicle(id);
	}

	private static boolean isSameFamily(String a, String b) {
		final String fa = a.contains("(") ? a.substring(0, a.indexOf('(')) : a;
		final String fb = b.contains("(") ? b.substring(0, b.indexOf('(')) : b;
		return fa.equals(fb);
	}

	/** 编组总长（含连接补偿） */
	public double getTotalLength() {
		double total = 0;
		for (Car car : cars) {
			final VehicleResource res = res0(car.vehicleId);
			total += res != null ? res.length : 12.0;
			if (res != null) {
				total += car.forward ? res.couplingPadding1 : res.couplingPadding2;
			}
		}
		return total;
	}
}

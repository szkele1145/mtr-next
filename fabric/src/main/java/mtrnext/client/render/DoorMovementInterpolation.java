package mtrnext.client.render;

/**
 * [0.36.2] 上游 MTR 4 的门位移插值层。
 * <p>
 * 上游 {@code ModelPropertiesPart.java:348} 对 Z 位移不直接用曲线值，而是：
 * <pre>
 * vehicle.persistentVehicleData.getInterpolatedDoorValue(
 *     doorAnimationType, doorZMultiplier, partDetails.flipped, doorOverrideValue, opening)
 * </pre>
 * 该方法（{@code PersistentVehicleData.java:98-113}）先算曲线值，再过一层
 * {@code DoorMovementInterpolation}（同文件 {@code L164-178}）：
 * <pre>
 * private static class DoorMovementInterpolation {
 *     private boolean opening;
 *     private final Interpolation interpolation = new Interpolation(500); // 500 ms
 *
 *     private float setAndGet(double value, boolean opening) {
 *         interpolation.setValue(value, this.opening != opening); // 方向翻转 → 重启插值
 *         this.opening = opening;
 *         return (float) interpolation.getValue();
 *     }
 * }
 * </pre>
 * 上游 {@code org.mtr.mod.resource.Interpolation} 是<b>线性</b>混合
 * （快照 {@code docs/_upstream/Interpolation.java}）：
 * <pre>
 * public void setValue(double value, boolean startInterpolation) {
 *     if (startInterpolation &amp;&amp; oldValue != value) {
 *         oldValue = getValue();                       // 从当前插值结果续接
 *         startMillis = System.currentTimeMillis();
 *     }
 *     latestValue = value;
 * }
 *
 * public double getValue() {
 *     final long difference = System.currentTimeMillis() - startMillis;
 *     if (difference < duration) {
 *         return (1 - difference / duration) * oldValue
 *              + difference / duration * latestValue;
 *     } else {
 *         oldValue = latestValue;
 *         return latestValue;
 *     }
 * }
 * </pre>
 * <p>
 * <b>作用</b>：消除曲线值在开门↔关门方向翻转处的跳变——翻转时不是瞬跳，
 * 而是从当前插值结果用 500 ms 线性过渡到新曲线值。
 * <p>
 * <b>注意</b>：上游只对 <b>Z</b> 位移套这层插值；X 位移走
 * {@code getDoorAnimationX}（{@code ModelPropertiesPart.java:347}），<b>不</b>插值。
 * <p>
 * <b>缓存粒度</b>：上游以 {@code doorZMultiplier + "_" + doorAnimationType + "_" + flipped}
 * 为 key，每个组合一个插值器，缓存在 {@code PersistentVehicleData} 里。
 * 本类用同样的 key 规则，保证不同部件互不干扰（上游同语义）。
 */
public final class DoorMovementInterpolation {

	/** 上游 {@code new Interpolation(500)} 的时长（毫秒） */
	public static final int DURATION = 500;

	private boolean opening;
	private final Interpolation interpolation = new Interpolation(DURATION);

	/**
	 * 设置当前曲线值并返回插值后的结果。
	 *
	 * @param value   曲线算出的 Z 位移（像素）
	 * @param opening true = 开门中，false = 关门中
	 */
	public float setAndGet(double value, boolean opening) {
		interpolation.setValue(value, this.opening != opening);
		this.opening = opening;
		return (float) interpolation.getValue();
	}

	/**
	 * [0.36.2] 上游 {@code org.mtr.mod.resource.Interpolation} 的等价实现
	 * （线性混合，duration 毫秒）。
	 */
	public static final class Interpolation {

		private long startMillis;
		private double oldValue;
		private double latestValue;

		private final int duration;

		public Interpolation(int duration) {
			this.duration = duration;
		}

		/**
		 * @param value              新值
		 * @param startInterpolation true = 从当前插值结果重启插值
		 */
		public void setValue(double value, boolean startInterpolation) {
			if (startInterpolation && oldValue != value) {
				oldValue = getValue();
				startMillis = System.currentTimeMillis();
			}
			latestValue = value;
		}

		public double getValue() {
			final long difference = System.currentTimeMillis() - startMillis;
			if (difference < duration) {
				return (1 - (float) difference / duration) * oldValue + (float) difference / duration * latestValue;
			} else {
				oldValue = latestValue;
				return latestValue;
			}
		}

		/**
		 * 直接设置当前值，不做插值（上游 {@code Interpolation.setValueDirect}）。
		 * <p>
		 * 上游用它做"立即跳到某值"的场景（如 Resource Pack Creator 的门调试覆盖）。
		 * mtr-next 暂未使用，但保留以保持与上游 API 一致。
		 */
		public void setValueDirect(double value) {
			startMillis = 0;
			oldValue = value;
			latestValue = value;
		}
	}

	/**
	 * [0.36.2] 上游的插值器缓存 key 规则：
	 * {@code doorZMultiplier + "_" + doorAnimationType + "_" + flipped}
	 * （{@code PersistentVehicleData.java:99}）。
	 * <p>
	 * 同样的 key 必须映射到<b>同一个</b>插值器实例——否则不同部件会各自
	 * 独立插值，与上游不符。
	 */
	public static String key(double doorZMultiplier, String doorAnimationType, boolean flipped) {
		return doorZMultiplier + "_" + doorAnimationType + "_" + flipped;
	}

	/**
	 * [0.36.7] key 的**前缀**部分（不含 {@code flipped}）。
	 * <p>
	 * 上游 key = {@code doorZMultiplier + "_" + doorAnimationType + "_" + flipped}，
	 * 其中前两段在**加载期就固定**。把前缀预先拼好，渲染期只需
	 * {@code prefix + flipped}，省掉每帧每部件两次字符串转换。
	 * <p>
	 * 与 {@link #key(double, String, boolean)} 生成的字符串**逐字相同**
	 * （{@code keyPrefix} 的结果再拼 {@code "_" + flipped}）。
	 */
	public static String keyPrefix(double doorZMultiplier, String doorAnimationType) {
		return doorZMultiplier + "_" + doorAnimationType + "_";
	}
}

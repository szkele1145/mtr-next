package mtrnext.client.render;

/**
 * [0.35] MTR 4.0 门动画曲线——上游 DoorAnimationType.getDoorAnimationX/Z 的
 * 逐分支移植（per-model 定义驱动，替代 3.x 的 per-train 单枚举）。
 * <p>
 * 上游语义（org.mtr.mod.resource.DoorAnimationType，MIT，
 * 仓库 Minecraft-Transit-Railway/Minecraft-Transit-Railway，master 分支，
 * 2026-07-16 提交，已逐行比对）：
 * <ul>
 * <li>multiplier 单位 = Blockbench 像素，渲染时 /16 转格
 * （ModelPropertiesPart.getOpenDoorBounds L241-242、addCube L524 同款 /16）；</li>
 * <li>time = doorValue ∈ [0,1]，1 = 完全开启；</li>
 * <li>位移方向由 multiplier 符号 + 翻转位决定：
 * {@code copySign(abs 曲线值, (flipped ? -1 : 1) * multiplier)}；</li>
 * <li>同一门洞左右门板靠相反符号的倍率对滑（ERIDU men2 z=+11 / men3 z=−11）。</li>
 * </ul>
 * 上游 flipped 来自 positionDefinitions 的 positionsFlipped（车头朝向镜像）。
 * 本实现的 flipped 由调用方按车头方向传入——bbmodel 本身不含朝向信息，
 * 但同一模型在正/反方向车头会整体镜像渲染，此时门位移方向必须反号，
 * 否则反向车头的门会朝车体外侧开。
 * <p>
 * 曲线实现与上游逐分支对齐（smoothEnds/PLUG/BOUNCY/MLR/R179/R211 全量），
 * 保证第三方 4.0 车包在 YMTR+NexT 下门动画手感与 MTR 4 一致。
 */
public enum DoorAnimation4x {

	STANDARD,
	STANDARD_SLOW,
	CONSTANT,
	PLUG_FAST,
	PLUG_SLOW,
	PLUG_SLOW_2,
	PLUG_SLOW_3,
	BOUNCY_1,
	BOUNCY_2,
	MLR,
	R179,
	R211;

	/** 解析 properties parts[].doorAnimationType 字符串；未知值回退 STANDARD（与上游默认一致） */
	public static DoorAnimation4x parse(String name) {
		if (name != null) {
			for (final DoorAnimation4x t : values()) {
				if (t.name().equals(name)) {
					return t;
				}
			}
		}
		return STANDARD;
	}

	/**
	 * X 轴（车宽方向）位移。返回像素值（调用方 /16）。
	 * PLUG 类是外摆门：先沿 X 向外弹开，再沿 Z 滑动。
	 *
	 * @param flipped 镜像标志（上游 positionDefinitions 的 positionsFlipped）。
	 *                镜像时位移整体反号——上游 copySign 语义：
	 *                {@code copySign(abs, (flipped ? -1 : 1) * multiplier)}。
	 */
	public float getDoorAnimationX(float multiplier, boolean flipped, float time) {
		return multiplier == 0 ? 0 : Math.copySign(getDoorAnimationXAbsolute(Math.abs(multiplier), time), (flipped ? -1.0F : 1.0F) * multiplier);
	}

	/**
	 * Z 轴（车长方向）位移。返回像素值（调用方 /16）。
	 * opening = 正在开门（用于关门时曲线分段不同的动画型）。
	 *
	 * @param flipped 镜像标志，语义同 {@link #getDoorAnimationX(float, boolean, float)}。
	 */
	public float getDoorAnimationZ(float multiplier, boolean flipped, float time, boolean opening) {
		return multiplier == 0 ? 0 : Math.copySign(getDoorAnimationZAbsolute(Math.abs(multiplier), time, opening), (flipped ? -1.0F : 1.0F) * multiplier);
	}

	private float getDoorAnimationXAbsolute(float multiplier, float time) {
		switch (this) {
			case PLUG_FAST:
			case PLUG_SLOW_2:
			case PLUG_SLOW_3:
				return time < 0.05 ? time * 20 * multiplier + 0.05F : multiplier + 0.05F;
			case PLUG_SLOW:
				return smoothEnds(0.05F, multiplier + 0.05F, 0, 0.1F, time);
			default:
				return 0;
		}
	}

	private float getDoorAnimationZAbsolute(float doorMax, float time, boolean opening) {
		switch (this) {
			case CONSTANT:
				return doorMax * clamp01(time / 0.5F);
			case PLUG_FAST:
				return smoothEnds(-doorMax, doorMax, -0.5F, 0.5F, time);
			case PLUG_SLOW:
				if (opening) {
					return smoothEnds(0, doorMax, 0.05F, 0.5F, time);
				} else {
					if (time > 0.5) {
						return smoothEnds(2, doorMax, 0.5F, 1, time);
					} else if (time < 0.3) {
						return smoothEnds(0, 2, 0.05F, 0.3F, time);
					} else {
						return 2;
					}
				}
			case PLUG_SLOW_2:
				return smoothEnds(-doorMax, doorMax, -0.75F, 0.75F, time);
			case PLUG_SLOW_3:
				return smoothEnds(-doorMax, doorMax, -1, 1, time);
			case BOUNCY_1:
				if (opening) {
					if (time > 0.4) {
						return smoothEnds(doorMax - 1, doorMax - 0.5F, 0.4F, 0.5F, time);
					} else {
						return smoothEnds(-doorMax + 1, doorMax - 1, -0.4F, 0.4F, time);
					}
				} else {
					if (time > 0.2) {
						return smoothEnds(1, doorMax - 0.5F, 0.2F, 0.5F, time);
					} else if (time > 0.1) {
						return smoothEnds(1.5F, 1, 0.1F, 0.2F, time);
					} else {
						return smoothEnds(-1.5F, 1.5F, -0.1F, 0.1F, time);
					}
				}
			case BOUNCY_2:
				if (opening) {
					if (time > 0.4) {
						return smoothEnds(doorMax - 0.5F, doorMax, 0.4F, 0.5F, time);
					} else {
						return smoothEnds(-doorMax + 0.5F, doorMax - 0.5F, -0.4F, 0.4F, time);
					}
				} else {
					if (time > 0.3) {
						return smoothEnds(1, doorMax, 0.3F, 0.5F, time);
					} else if (time > 0.1) {
						return smoothEnds(3, 1, 0.1F, 0.3F, time);
					} else {
						return smoothEnds(-3, 3, -0.1F, 0.1F, time);
					}
				}
			case MLR:
				if (opening) {
					if (time < 0.2) {
						return 0;
					} else if (time > 0.7) {
						return doorMax;
					} else {
						return (time - 0.2F) * 2 * doorMax;
					}
				} else {
					final float stoppingPoint = 1.5F;
					if (time > 0.25) {
						return Math.min((time - 0.25F) * 2 * doorMax + stoppingPoint + 1, doorMax);
					} else if (time > 0.2) {
						return smoothEnds(stoppingPoint, stoppingPoint + 2, 0.2F, 0.3F, time);
					} else if (time < 0.1) {
						return time / 0.1F * stoppingPoint;
					} else {
						return stoppingPoint;
					}
				}
			case R179:
				if (opening) {
					if (time > 0.4) {
						return smoothEnds(doorMax - 1, doorMax - 0.5F, 0.4F, 0.6F, time);
					} else {
						return smoothEnds(-doorMax + 1, doorMax - 1, -0.4F, 0.4F, time);
					}
				} else {
					if (time > 0.2) {
						return smoothEnds(1, doorMax - 0.5F, 0.2F, 0.6F, time);
					} else {
						return smoothEnds(-1.5F, 1.5F, -0.4F, 0.4F, time);
					}
				}
			case R211:
				if (time < 0.2) {
					return 0;
				} else if (time > 0.7) {
					return doorMax;
				} else {
					return (time - 0.2F) * 2 * doorMax;
				}
			case STANDARD_SLOW:
				if (!opening) {
					if (time > 0.2) {
						return smoothEnds(1, doorMax, 0.2F, 0.5F, time);
					} else if (time > 0.1) {
						return 1;
					} else {
						return smoothEnds(0, 1, 0, 0.1F, time);
					}
				}
				return smoothEnds(0, doorMax, 0, 0.5F, time);
			default: // STANDARD
				return smoothEnds(0, doorMax, 0, 0.5F, time);
		}
	}

	private static float smoothEnds(float startValue, float endValue, float startTime, float endTime, float time) {
		if (time < startTime) {
			return startValue;
		}
		if (time > endTime) {
			return endValue;
		}
		final float timeChange = endTime - startTime;
		final float valueChange = endValue - startValue;
		return valueChange * (1 - (float) Math.cos(Math.PI * (time - startTime) / timeChange)) / 2 + startValue;
	}

	private static float clamp01(float v) {
		return v < 0 ? 0 : Math.min(v, 1);
	}
}

package mtrnext.client.render;

import mtrnext.MtrNext;
import net.minecraft.client.model.geom.ModelPart;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [0.11] 门开度的渐进插值器（替代 0.10 的布尔瞬时开合）。
 * <p>
 * renderCar 的 doorLeftOpen/doorRightOpen 是瞬时布尔，但 3.x 的
 * TrainClient 有 doorValue（0~1 渐进值，javap 实证字段存在）。
 * 0.10 直接跳变（0→1），视觉生硬；本插值器按帧率平滑追踪目标值：
 * 每帧向目标推进 rate×delta（开门 0.5/s、关门 0.8/s——门机快关慢开）。
 * <p>
 * 每列车实例一个状态（编组屏预览与车厂渲染共用同一模型 bake，
 * 但门状态按 ModelPart 实例隔离——ModelPart 每列车独立 bake 时的
 * part 对象即状态载体，无需外部 Map 的 per-train 键）。
 */
public final class DoorAnimator {

	/** 每秒开/关速率（0→1 的推进速度） */
	private static final float OPEN_RATE = 0.5F;
	private static final float CLOSE_RATE = 0.8F;

	/**
	 * 平滑推进门部件开度到目标。
	 *
	 * @param doorPart   门部件（baked ModelPart 的 door_ child）
	 * @param openTarget 目标开度（0=全关, 1=全开）
	 * @param delta      帧间隔（秒）
	 */
	public static void tick(ModelPart doorPart, float openTarget, float delta) {
		if (doorPart == null) {
			return;
		}
		// part.z 即当前开度状态（负值=开）— [0.10] 的状态载体复用
		final float current = -doorPart.z / 0.75F;
		float next = current;
		if (next < openTarget) {
			next = Math.min(openTarget, current + OPEN_RATE * delta);
		} else if (next > openTarget) {
			next = Math.max(openTarget, current - CLOSE_RATE * delta);
		}
		doorPart.z = -0.75F * next;
	}

	/** 便捷入口：布尔目标（0.10 兼容） */
	public static void tickBool(ModelPart doorPart, boolean open, float delta) {
		tick(doorPart, open ? 1.0F : 0.0F, delta);
	}
}

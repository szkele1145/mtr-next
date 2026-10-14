package mtrnext.util;

import net.minecraft.util.Mth;

/**
 * [0.19] 播放概率工具 — Joint（轨道节点音）的速度相关触发概率。
 * <p>
 * BVE 语义：每经过一个轨道节点播一次 point.wav。YMTR 的
 * TrainSoundBase 回调无节点边沿信号——用概率近似：
 * 40km/h 时 chance(0.25) ≈ 每 4 次回调响一次 ≈ 节点 0.8 格
 * 间距的通过频率（40km/h = 2 格/tick 的回调节奏）。
 */
public final class TickRandom {

	private TickRandom() {
	}

	/** chance(p) = 以概率 p 返回 true（p 钳制 0~1） */
	public static boolean chance(double p) {
		if (p <= 0) {
			return false;
		}
		if (p >= 1) {
			return true;
		}
		return Math.random() < p; // 回调线程无关 MC 的 Level 随机源(独立)
	}
}

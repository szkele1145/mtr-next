package mtrnext.client;

import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.data.RailType;
import mtr.data.TransportMode;
import mtrnext.MtrNext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * [0.3] 曲线精确预览——用 YMTR 自己的 Rail 几何构造预览轨道。
 * <p>
 * javap 实证：{@code new Rail(BlockPos, RailAngle, BlockPos, RailAngle,
 * RailType, TransportMode)}（六参构造器）+ {@code getPosition(double)}
 * 沿弧长采样 + {@code RailAngle.fromAngle(float)} 由度数取最近对齐角。
 * <p>
 * 这保证预览形状与正式生成的轨道<b>逐点一致</b>（同一套贝塞尔实现），
 * 优于 4.x 的独立预览管线。
 * <p>
 * 采样策略：64 段（曲线平滑），首末端从方块中心抬高 0.5 对齐轨道渲染高度。
 */
public final class RailPreviewGeometry {

	private static final int SEGMENTS = 64;

	private RailPreviewGeometry() {
	}

	/**
	 * 构造与正式轨道同几何的预览折线。
	 *
	 * @return 采样点数组（世界坐标），null=无法构造
	 */
	public static Vec3[] sampleCurve(Minecraft mc, BlockPos first, BlockPos second) {
		try {
			// 方向角：第一节点→第二节点（度）→ RailAngle 最近对齐
			final double dx = second.getX() - first.getX();
			final double dz = second.getZ() - first.getZ();
			final float angle = (float) Math.toDegrees(Math.atan2(dz, dx));
			final RailAngle fromAngle = RailAngle.fromAngle(angle);
			final RailAngle toAngle = RailAngle.fromAngle(angle + 180F);

			final Rail previewRail = new Rail(
					first, fromAngle,
					second, toAngle,
					RailType.values()[0],  // 第一种轨道类型（预览形状与类型无关——曲线几何同源）
					TransportMode.TRAIN);

			final double length = previewRail.getLength();
			if (length < 0.5) {
				return null;
			}
			final Vec3[] points = new Vec3[SEGMENTS + 1];
			for (int i = 0; i <= SEGMENTS; i++) {
				final Vec3 pos = previewRail.getPosition(length * i / SEGMENTS);
				points[i] = pos == null ? null : new Vec3(pos.x, pos.y + 0.5, pos.z);
			}
			return points;
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("preview geometry failed", t);
			return null;
		}
	}
}

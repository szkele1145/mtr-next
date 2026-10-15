package mtrnext.client;

import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.data.RailType;
import mtr.data.TransportMode;
import mtrnext.MtrNext;
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
 * <b>[0.32] 类型无关性经字节码证明</b>（此前只是注释里的断言）：
 * <pre>
 * javap -p -c -cp &lt;ymtr-jar&gt; mtr.data.Rail
 * </pre>
 * <ul>
 *   <li>{@code getPosition(double)} 的指令序列只读纯几何字段
 *       {@code tEnd1/tStart1/tEnd2/tStart2}、{@code h1/k1/r1/reverseT1}、
 *       {@code isStraight1}、{@code h2/k2}，以及 {@code getPositionY(double)}
 *       与私有静态 {@code getPositionXZ(DDDDDZ)}——
 *       <b>全程没有 {@code getfield railType}，也没有 {@code railSlopeStyle}
 *       或 {@code speedLimit} 的读取</b>；</li>
 *   <li>六参构造器对 RailType 只出现一次
 *       {@code putfield railType:Lmtr/data/RailType;}（第 18 条指令），
 *       即<b>纯存储</b>：没有 RailType 常量的 {@code getstatic}，没有
 *       {@code RailSlopeStyle} 引用，也没有任何按类型/模式分支的判断。</li>
 * </ul>
 * 因此贝塞尔几何与 {@code RailType}/{@code TransportMode}/
 * {@code railSlopeStyle}/{@code speedLimit} 全部无关，下面传入哪种轨道类型
 * 都不会改变采样结果。
 * <p>
 * 采样策略：64 段（曲线平滑），首末端从方块中心抬高 0.5 对齐轨道渲染高度。
 */
public final class RailPreviewGeometry {

	private static final int SEGMENTS = 64;

	/**
	 * [0.32] 几何占位用的轨道类型，固定取枚举首项（WOODEN）。
	 * <p>
	 * 缓存为常量而非每次调用 {@code RailType.values()[0]}：{@code values()}
	 * 每次都会克隆整个 16 元素枚举数组，而本方法在预览渲染的每一帧都被调用，
	 * 属于无谓的逐帧分配。
	 */
	private static final RailType PLACEHOLDER_RAIL_TYPE = RailType.values()[0];

	private RailPreviewGeometry() {
	}

	/**
	 * 构造与正式轨道同几何的预览折线。
	 *
	 * @return 采样点数组（世界坐标），null=无法构造
	 */
	public static Vec3[] sampleCurve(BlockPos first, BlockPos second) {
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
					PLACEHOLDER_RAIL_TYPE,  // 见类注释：几何与类型无关（javap -c 实证）
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

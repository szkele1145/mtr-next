package mtrnext.client;

import mtr.client.ClientData;
import mtr.data.Rail;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * 预览几何——直接取客户端已有的<b>真实轨道</b>（{@code ClientData.RAILS}）。
 *
 * <p><b>[0.38.9] 只渲染真实数据，不再自己构造。</b>
 *
 * <p>MTR 4.0 的预览（{@code org.mtr.mod.render.RenderRails}）：
 * 形状完全来自 {@code org.mtr.core.data.Rail} 对象，贴图 {@code rail_preview.png}
 * 只负责上色。而 3.x 的等价物是 {@code mtr.data.Rail} + {@code ClientData.RAILS}
 * （由 {@code write_rails} 包同步），几何算法同源（两段参数 + h1/k1/r1 控制点）。
 *
 * <p>⇒ 直接取真实 rail 采样，位置必然正确；没有真实 rail 时不画（避免误导）。
 */
public final class RailPreviewGeometry {

	private static final int SEGMENTS = 96;

	private RailPreviewGeometry() {
	}

	public static final class Preview {
		/** 轨道对象本身（几何已经从它采样出来；仅作诊断/备用） */
		public final Rail rail;
		public final Vec3[] points;
		public final double length;
		public final double deltaY;
		public final double slopePercent;
		/**
		 * [0.47.0] 坡度换算成角度（{@code atan(slope/100)}，单位度）。
		 *
		 * <p>为什么两个都要给：MTR 玩家习惯说「千分之几 / 百分比」，
		 * 而施工视角更直观的是「多少度」。HUD 同时显示两者。
		 */
		public final double slopeDeg;
		/**
		 * [0.47.0] 整条预览的<b>弦向</b>罗盘方位角：0=北，90=东，180=南，270=西。
		 *
		 * <p>为什么用「罗盘方位」而不是 {@code atan2} 原始值：
		 * MC 世界 +X=东、+Z=南，直接 {@code atan2(dz,dx)} 得到的是「东为 0、南为 90」
		 * 的数学角，念出来是反的（往北走会变成 -90°）。F3 与所有指南针 mod
		 * 都用「北为 0、顺时针」，这里与之对齐。
		 */
		public final double headingDeg;
		/** [0.47.0] 起点处切线方位角（度，罗盘制） */
		public final double startHeadingDeg;
		/** [0.47.0] 终点处切线方位角（度，罗盘制） */
		public final double endHeadingDeg;

		Preview(Rail rail, Vec3[] points, double length, double deltaY, double slopePercent,
				double slopeDeg, double headingDeg, double startHeadingDeg, double endHeadingDeg) {
			this.rail = rail;
			this.points = points;
			this.length = length;
			this.deltaY = deltaY;
			this.slopePercent = slopePercent;
			this.slopeDeg = slopeDeg;
			this.headingDeg = headingDeg;
			this.startHeadingDeg = startHeadingDeg;
			this.endHeadingDeg = endHeadingDeg;
		}
	}

	/**
	 * [0.47.0] 两点之间的罗盘方位角（0=北，90=东，180=南，270=西）。
	 *
	 * <p>推导：MC 里 +X=东、+Z=南 ⇒ 北方向是 -Z。
	 * 方位角 = {@code atan2(dx, -dz)}，再归一到 [0,360)。
	 * 校验：往东 dx&gt;0 ⇒ 90°；往北 dz&lt;0 ⇒ 0°；往南 ⇒ 180°；往西 ⇒ 270°。
	 */
	public static double bearingDeg(double ax, double az, double bx, double bz) {
		final double deg = Math.toDegrees(Math.atan2(bx - ax, -(bz - az)));
		return (deg % 360.0 + 360.0) % 360.0;
	}

	/** 取两点之间已同步的真实轨道并采样 */
	public static Preview build(Minecraft mc, BlockPos first, BlockPos second) {
		try {
			final Map<BlockPos, Map<BlockPos, Rail>> rails = ClientData.RAILS;
			if (rails == null) {
				return null;
			}
			Rail rail = null;
			final Map<BlockPos, Rail> fromFirst = rails.get(first);
			if (fromFirst != null) {
				rail = fromFirst.get(second);
			}
			if (rail == null) {
				final Map<BlockPos, Rail> fromSecond = rails.get(second);
				if (fromSecond != null) {
					rail = fromSecond.get(first);
				}
			}
			if (rail == null) {
				return null;
			}
			return sample(rail);
		} catch (final Throwable t) {
			return null;
		}
	}
	/**
	 * 构造「待建轨道」的预览几何 —— <b>[0.53.0] 恢复成 0.45.1 的字节码实现</b>。
	 *
	 * <p>0.45.1 的 {@code mtr-next-0.45.1-1.20.1-fabric.jar} 反编译实证
	 * （{@code RailPreviewGeometry.buildGhost} 字节码 19-139）：
	 * <pre>
	 *   s1 = level.getBlockState(first);   s2 = level.getBlockState(second);
	 *   if (!(s1.getBlock() instanceof BlockNode) || !(s2.getBlock() instanceof BlockNode)) return null;
	 *   a1  = BlockNode.getAngle(s1);      // first 的朝向
	 *   a2  = BlockNode.getAngle(s2);      // second 的朝向
	 *   a12 = toDegrees(atan2(second.z - first.z, second.x - first.x));
	 *   deg1 = a1 + (similarFacing(a1, a12) ? 0   : 180);
	 *   deg2 = a2 + (similarFacing(a2, a12) ? 180 : 0  );
	 *   new Rail(first, fromAngle(deg1), second, fromAngle(deg2), QUARTZ, TRAIN);
	 * </pre>
	 * 调用方传的是 {@code (mc, firstNodeFromHand(), hitResult.getBlockPos())}，
	 * 即 <b>first = 已选定的一号节点、second = 准心所指的二号节点</b>。
	 *
	 * <p><b>★ [0.54.0] 这段角度公式「是否真的对」——已用字节码逐行核对，结论：对。</b>
	 *
	 * <p>权威对照是 YMTR 官方 {@code mtr.item.ItemNodeModifierBase.onEndClick}
	 * （CFR 反编译 libs/ymtr-fabric-1.20.1.jar）：
	 * <pre>
	 *   float angle1 = BlockNode.getAngle(stateStart);
	 *   float angle2 = BlockNode.getAngle(stateEnd);
	 *   float angleDifference = toDegrees(atan2(posEnd.z - posStart.z, posEnd.x - posStart.x));
	 *   RailAngle railAngleStart = fromAngle(angle1 + (similarFacing(angleDifference, angle1) ? 0 : 180));
	 *   RailAngle railAngleEnd   = fromAngle(angle2 + (similarFacing(angleDifference, angle2) ? 180 : 0));
	 * </pre>
	 * 与上方伪代码<b>逐项等价</b>：
	 * <ul>
	 *   <li>{@code similarFacing} 的参数顺序不影响结果——javap 其实现在于
	 *       {@code abs(normalizeAngle(angle1 - angle2)) < 90}，对两个参数<b>对称</b>；</li>
	 *   <li>两处 {@code 0/180} 的分配位置完全一致（起点同向取 0、终点取反向 180）。</li>
	 * </ul>
	 *
	 * <p><b>但 {@code getAngle} 拿不到完整 360°</b>（这也是为什么必须靠 {@code similarFacing} 补 180°）：
	 * javap 实证 {@code mtr.block.BlockNode.getAngle} 是
	 * {@code (FACING ? 0 : 90) + (IS_22_5 ? 22.5F : 0) + (IS_45 ? 45F : 0)}，
	 * 值域只有 <b>0..157.5°</b>；180..337.5° 这半边朝向在方块状态里<b>不存在</b>。
	 * ⇒ 3.x 官方自己也只可能这么算（同样只有这个 API）。
	 *
	 * <p>⚠️ 我曾在 0.47 把两端顺序「修正」成 onEndClick 的写法（pos1=准心），
	 * 那会让曲线鼓向另一侧 —— 用户已确认 0.45.1 的观感，所以这里按 0.45.1 原样恢复。
	 *
	 * <p>轨道类型必须是 <b>QUARTZ</b>：原生 5 参渲染器只有
	 * {@code previewFlag && railType == QUARTZ} 时才用
	 * {@code mtr:textures/block/rail_preview.png}（白色稀疏那张，即用户说的「白线」）。
	 *
	 * <p>[0.54.0] 即便公式与官方等价，仍可能因为「节点朝向与连线差太多」而造出几十格的
	 * 病态样条（真机实测 5 格距离 → 55.72 格轨道）。调用方
	 * {@code RailPreviewRenderer} 对此加了长度护栏：不合理的预览<b>直接不画</b>。
	 *
	 * @param first  物品 NBT 里记录的一号节点
	 * @param second 准心所指的二号节点
	 */
	public static Preview buildGhost(Minecraft mc, BlockPos first, BlockPos second) {
		try {
			final net.minecraft.world.level.Level level = mc == null ? null : mc.level;
			if (level == null) {
				return null;
			}
			final net.minecraft.world.level.block.state.BlockState s1 = level.getBlockState(first);
			final net.minecraft.world.level.block.state.BlockState s2 = level.getBlockState(second);
			if (!(s1.getBlock() instanceof mtr.block.BlockNode)
					|| !(s2.getBlock() instanceof mtr.block.BlockNode)) {
				return null;
			}

			final float a1 = mtr.block.BlockNode.getAngle(s1);
			final float a2 = mtr.block.BlockNode.getAngle(s2);
			final float a12 = (float) Math.toDegrees(Math.atan2(
					(double) (second.getZ() - first.getZ()),
					(double) (second.getX() - first.getX())));

			final float deg1 = a1 + (mtr.data.RailAngle.similarFacing(a1, a12) ? 0F : 180F);
			final float deg2 = a2 + (mtr.data.RailAngle.similarFacing(a2, a12) ? 180F : 0F);

			final Rail rail = new Rail(first, mtr.data.RailAngle.fromAngle(deg1),
					second, mtr.data.RailAngle.fromAngle(deg2),
					mtr.data.RailType.QUARTZ, mtr.data.TransportMode.TRAIN);
			return sample(rail);
		} catch (final Throwable t) {
			return null;
		}
	}

	/** 两点之间是否已有真实（已同步）轨道 */
	public static boolean hasRealRail(BlockPos first, BlockPos second) {
		try {
			final Map<BlockPos, Map<BlockPos, Rail>> rails = ClientData.RAILS;
			if (rails == null) {
				return false;
			}
			final Map<BlockPos, Rail> a = rails.get(first);
			if (a != null && a.containsKey(second)) {
				return true;
			}
			final Map<BlockPos, Rail> b = rails.get(second);
			return b != null && b.containsKey(first);
		} catch (final Throwable t) {
			return false;
		}
	}

	/** 采样一条 rail，并顺带算出 4.0 风格的数据（长度/坡度/方位） */
	static Preview sample(Rail rail) {
		try {
			final double length = rail.getLength();
			if (!(length > 0.5) || !Double.isFinite(length)) {
				return null;
			}
			final Vec3[] points = new Vec3[SEGMENTS + 1];
			for (int i = 0; i <= SEGMENTS; i++) {
				final Vec3 p = rail.getPosition(length * i / SEGMENTS);
				if (p == null) {
					return null;
				}
				points[i] = p;
			}
			final Vec3 a = points[0];
			final Vec3 b = points[SEGMENTS];
			final double deltaY = b.y - a.y;
			final double h = Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z));
			final double slopePercent = h > 1.0E-4 ? (deltaY / h) * 100.0 : 0.0;
			final double slopeDeg = Math.toDegrees(Math.atan2(deltaY, h));

			// 弦向（整条轨道的平均走向）
			final double headingDeg = bearingDeg(a.x, a.z, b.x, b.z);
			// 首尾切线（弯道时与弦向不同，HUD 只用弦向，切线留作诊断/后续扩展）
			final int k = Math.min(8, SEGMENTS);
			final double startHeadingDeg = bearingDeg(
					points[0].x, points[0].z, points[k].x, points[k].z);
			final double endHeadingDeg = bearingDeg(
					points[SEGMENTS - k].x, points[SEGMENTS - k].z, points[SEGMENTS].x, points[SEGMENTS].z);

			return new Preview(rail, points, length, deltaY, slopePercent,
					slopeDeg, headingDeg, startHeadingDeg, endHeadingDeg);
		} catch (final Throwable t) {
			return null;
		}
	}
}
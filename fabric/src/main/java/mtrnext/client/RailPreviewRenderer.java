package mtrnext.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 轨道预览渲染器 —— <b>[0.52.0] 定稿：回退到 YMTR 原生轨道渲染器 + 只加数据面板</b>。
 *
 * <p>用户原话：「算了，我不要 4.0 那种到哪哪就预览了，我要选定 1 号节点之后，
 * 准心放在 2 号节点上才会预览…… 前面的版本，除了没有信息显示和颜色都没问题」。
 * ⇒ 几何一律交回原生渲染器，<b>不自绘任何顶点</b>，颜色暂不做。
 *
 * <p><b>★ 为什么自绘必须全部删掉（真机事故）</b>
 * <ul>
 *   <li>0.49/0.50 在 {@code RenderTrains.render} 的 RETURN 里用
 *       {@code Tesselator.getInstance().getBuilder()} + {@code ts.end()}
 *       ⇒ 打断 MTR 尚未提交的排队批次 ⇒ 真机「轨道渲染错位漂移 / 增生一大堆」；</li>
 *   <li>0.52 改用 {@code MultiBufferSource + RenderType.debugQuads()} 不再打断队列，
 *       但用户已经明确不要「准心指哪都预览」这套交互，遂整体回退。</li>
 * </ul>
 *
 * <p><b>★ 原生渲染器用法（0.44.1 起验证可用，用户确认「轨道渲染正常了」）</b>
 * <pre>
 *   RenderTrains.renderRailStandardSafe(
 *       Level world, Rail rail, float yOffset, boolean previewFlag, float railWidth)
 * </pre>
 * 反编译实证：第 5 个参数是 <b>railWidth（轨道半宽，格）</b>，不是深度；
 * 传 0 会得到宽度为 0 的退化四边形（此前「预览只有一条细线」的真因）。
 * {@code previewFlag && railType == QUARTZ} 才会选
 * {@code mtr:textures/block/rail_preview.png}（带命名空间的那张），
 * 所以 {@link RailPreviewGeometry#buildGhost} 构造的轨道类型必须是 QUARTZ。
 *
 * <p>预览出现条件（严格，缺一不画）：
 * <ol>
 *   <li>手持轨道相关物品（{@code RenderTrains.isHoldingRailRelated}）；</li>
 *   <li>物品 NBT 里已存一号节点（{@link PreviewState#firstNodeFromHand()}）；</li>
 *   <li>准心命中了方块；</li>
 *   <li>准心所指 ≠ 一号节点；</li>
 *   <li>两点距离 ≤ 5 格；</li>
 *   <li>两端都是 MTR 节点方块（见 {@link RailPreviewGeometry#buildGhost}）。</li>
 * </ol>
 */
public final class RailPreviewRenderer {

	/** 二号节点的最大选取距离（格）—— 用户定稿的交互就是 5 格内 */
	private static final double MAX_NODE_REACH = 5.0D;
	/** 抬高量（格）：与 YMTR 原生真实轨道一致（1.05/16 = 0.065625） */
	private static final float RAIL_Y_OFFSET = 0.065625F;
	/** 轨道半宽（格）：原生真实轨道同款 1.0 ⇒ 总宽 2 格 */
	private static final float RAIL_WIDTH = 1.0F;
	/** 预览贴图（带命名空间；5 参版本只在 railType==QUARTZ 时才会选它） */
	private static final String PREVIEW_TEXTURE = "mtr:textures/block/rail_preview.png";

	private static long MTRNEXT$DIAG_AT = 0L;
	private static boolean MTRNEXT$CALLED_ONCE = false;

	private RailPreviewRenderer() {
	}

	/** 由 {@code RenderTrainsPreviewMixin}（MTR 渲染末尾）调用 */
	public static void draw(Minecraft mc) {
		if (!MTRNEXT$CALLED_ONCE) {
			MTRNEXT$CALLED_ONCE = true;
			mtrnext.MtrNext.LOGGER.info("[preview] 渲染回调已被调用（首次）");
		}
		try {
			// ★ [0.54.0] 开发者自检类已<b>整类删除</b>，这里不再有任何「旁路」。
			//
			// 事故复现（真机日志 2026-10-24 16:38:04，0.53.0 + run/mtrnext-preview-selftest.flag）：
			//   [preview][selftest] 已接管预览 a1=… b1=… flat=len=55.72 …
			//   ——两点只相距 5 格，却画出一条 55.72 格的白轨（QUARTZ ⇒ rail_preview.png
			//   就是白色那张），横跨半个视野 ⇒ 用户看到的「又轨道满天飞，这次是白色的轨道」。
			// 根因不是公式错，而是「一个文件的存在改变了玩家看到的预览」。这条旁路已从
			// 生产路径彻底消失：本类不经任何外部开关，只走下面的严格交互条件。
			if (mc == null || mc.player == null || mc.level == null) {
				diag("mc/player/level null");
				return;
			}
			if (!mtr.render.RenderTrains.isHoldingRailRelated(mc.player)) {
				diag("未手持轨道相关物品（holdingRailRelated=false）");
				return;
			}
			if (!(mc.hitResult instanceof BlockHitResult hit)) {
				diag("准心没有命中方块");
				return;
			}
			final BlockPos selected = PreviewState.firstNodeFromHand();
			if (selected == null) {
				diag("手持物品无 pos（未选一号节点）；" + handDebug(mc));
				return;
			}
			final BlockPos crosshair = hit.getBlockPos();
			if (crosshair.equals(selected)) {
				diag("准心就在一号节点上");
				return;
			}
			final double reach = hit.getLocation().distanceTo(mc.player.getEyePosition());
			if (reach > MAX_NODE_REACH) {
				diag(String.format("超出 5 格: %.2f", reach));
				return;
			}
			// ★ [0.53.2] 严格回到 0.45.1：两点之间已经有真实轨道 → 直接不画。
			//
			// 为什么不能用 build() 把「真实轨道」也画出来：真实轨道的 railType 是 IRON/其它，
			// 而 renderRailStandardSafe(previewFlag=true) 的贴图选择是
			//   previewFlag && railType==QUARTZ ? "mtr:textures/block/rail_preview.png"
			//                                   : "textures/block/rail.png"   ← 缺 mtr: 前缀
			// ⇒ 真实轨道走这条会被解析成不存在的 minecraft:textures/block/rail.png
			//   ⇒ 真机表现为「紫黑缺失贴图」。这正是上一版 9 参兜底看到的现象。
			// 0.45.1 的字节码就是「hasRealRail → return，然后只画 buildGhost 的待建轨道」，
			// 那条待建轨道被构造成 QUARTZ ⇒ 稳定拿到白色 rail_preview.png（用户说的「白线」）。
			if (RailPreviewGeometry.hasRealRail(selected, crosshair)) {
				diag("该两点已有真实轨道");
				return;
			}
			final RailPreviewGeometry.Preview ghost = RailPreviewGeometry.buildGhost(mc, selected, crosshair);
			if (ghost == null || ghost.rail == null) {
				diag("ghost 构造失败（两端都要是 MTR 节点方块）first=" + selected + " second=" + crosshair);
				return;
			}
			// ★ [0.54.0] 几何合理性护栏 —— 长度明显不合理的预览一律不画。
			//
			// <b>为什么需要这道闸（实测数据，不是猜）</b>
			//   16:38:04 日志：两端相距 5 格，MTR 造出的 rail 却是 len=55.72 格（11.1 倍）。
			//   机制：{@code new Rail(pos1, angle1, pos2, angle2, …)} 的两端「出射角」必须
			//   与两点连线相容。而角度只能由 {@code BlockNode.getAngle} 得到，javap 实证
			//   （{@code mtr.block.BlockNode.getAngle}）它是
			//   {@code (FACING ? 0 : 90) + (IS_22_5 ? 22.5 : 0) + (IS_45 ? 45 : 0)}，
			//   <b>值域只有 0..157.5°，拿不到 180..337.5° 的完整朝向</b>；180° 的那一半只能靠
			//   {@code RailAngle.similarFacing(angle, 连线角)} 逐端补出来（YMTR 官方
			//   {@code mtr.item.ItemNodeModifierBase.onEndClick} 字节码即此写法，本类照抄）。
			//   一旦某个节点的实际朝向与连线差得多（节点是斜放/反向放的），样条就会甩出
			//   一条几十格的大弧 —— 在屏幕上正是「轨道满天飞」。
			//   ⇒ 宁可这一次不画预览（用户看不到任何东西，不会误解），也绝不把巨弧画上屏。
			//
			// <b>阈值为什么是 4 倍 + 6 格</b>：两点最大 5 格（MAX_NODE_REACH），正常弯道
			//   长度最多约为直线距离的 1.6 倍；两条 45° 斜线的极端弯也只到 2 倍左右。
			//   4 倍 + 6 格（5 格距离 ⇒ 上限 26 格）给足合法弯道余量，同时把 55 格那种
			//   明显病态的样条挡在外面。
			final double straight = Math.sqrt(
					(double) (crosshair.getX() - selected.getX()) * (crosshair.getX() - selected.getX())
							+ (double) (crosshair.getZ() - selected.getZ()) * (crosshair.getZ() - selected.getZ())
							+ (double) (crosshair.getY() - selected.getY()) * (crosshair.getY() - selected.getY()));
			if (ghost.length > straight * 4.0D + 6.0D) {
				diag(String.format(
						"几何不合理，拒绝预览：直线=%.2f 格 轨道长=%.2f 格（%.1f 倍）first=%s second=%s",
						straight, ghost.length, ghost.length / Math.max(1.0E-3D, straight), selected, crosshair));
				return;
			}
			diag(String.format(
					"渲染预览 first=%s second=%s pts=%d len=%.2fm slope=%+.2f%% (%+.2f°) heading=%.1f°",
					selected, crosshair, ghost.points.length, ghost.length,
					ghost.slopePercent, ghost.slopeDeg, ghost.headingDeg));
			// 数据面板（GUI 阶段画字，见 RailPreviewHud）
			RailPreviewHud.update(ghost, crosshair, selected, false);
			drawNative(mc, ghost);
		} catch (final Throwable t) {
			mtrnext.MtrNext.LOGGER.warn("[preview] render failed", t);
		}
	}

	/**
	 * 交回 YMTR 原生轨道渲染器 —— <b>严格恢复 0.45.1 的做法</b>（用户确认可用的版本）。
	 *
	 * <p><b>[0.53.1] 去掉 9 参兜底分支。</b>
	 *
	 * <p>上一版为了兼容「真实轨道类型不是 QUARTZ」而加了一条 9 参路径，但：
	 * <ul>
	 *   <li>9 参路径之前实测会渲染成**紫黑缺失贴图**（传过不存在的 rail.png）；</li>
	 *   <li>它偏离了 0.45.1，而 0.45.1 是用户明确认可可用的状态。</li>
	 * </ul>
	 * ⇒ 一律用 0.45.1 的 5 参调用：
	 * <pre>
	 *   renderRailStandardSafe(level, rail, 0.065625F, true, 1.0F)
	 *     yOffset = 0.065625F（原生真实轨道的抬高）
	 *     previewFlag = true
	 *     railWidth = 1.0F  ← 关键：0.0F 会退化成一条细线
	 * </pre>
	 */
	static void drawNative(Minecraft mc, RailPreviewGeometry.Preview preview) {
		if (mc == null || mc.level == null || preview == null || preview.rail == null) {
			return;
		}
		mtrnext.mixin.RenderTrainsRailInvoker.mtrnext$renderRailSafe5(
				mc.level, preview.rail, RAIL_Y_OFFSET, true, RAIL_WIDTH);
	}

	/**
	 * 手里那个物品的 id + NBT 键（诊断「为什么读不到一号节点」）。
	 *
	 * <p>YMTR 的 {@code ItemBlockClickingBase.use} 字节码：<b>只有服务端</b>才写 NBT
	 * （{@code world.isClient} 为真时直接走 super 返回），写的是
	 * {@code ItemBlockClickingBase.TAG_POS}（常量值 {@code "pos"}，long 形式的 BlockPos），
	 * 第二次点击后 {@code remove("pos")}。所以客户端看到的是服务端回同步的结果。
	 */
	private static String handDebug(Minecraft mc) {
		try {
			final net.minecraft.world.item.ItemStack stack = mc.player.getMainHandItem();
			final net.minecraft.nbt.CompoundTag tag = stack.getTag();
			return "手持=" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
					+ " nbt=" + (tag == null ? "null" : tag.getAllKeys().toString());
		} catch (final Throwable t) {
			return "handDebug failed";
		}
	}

	private static void diag(String msg) {
		final long now = System.currentTimeMillis();
		if (now - MTRNEXT$DIAG_AT > 3000L) {
			MTRNEXT$DIAG_AT = now;
			mtrnext.MtrNext.LOGGER.info("[preview] {}", msg);
		}
	}
}

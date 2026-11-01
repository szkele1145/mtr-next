package mtrnext.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;

import java.util.Locale;

/**
 * [0.47.0] 轨道预览的「MTR 4.0 风格」实时数据面板。
 *
 * <p><b>为什么单独一个类 + HudRenderCallback</b>
 *
 * <p>几何是在 {@code RenderTrains.render} 的 RETURN 里画的（世界空间、相机相对坐标），
 * 那个阶段<b>没有</b> {@link GuiGraphics}，硬塞文字要么自己拼 {@code Font.drawInBatch}
 * （需要 {@code Matrix4f} + {@code MultiBufferSource}，矩阵状态还要自己保证），
 * 要么调 1.20.1 里<b>并不存在</b>的 {@code Font.drawShadow}（编译直接失败）。
 * Fabric API 的 {@code HudRenderCallback} 在 GUI 阶段回调、矩阵干净，
 * 因此：世界阶段只负责<b>把数据丢进静态字段</b>，GUI 阶段负责画字。
 *
 * <p><b>为什么要有超时</b>：预览只在「手持轨道连接器 + 已选一号节点 + 准心指向某处」时出现。
 * 预览消失后 HUD 也必须消失，而数据字段最后一次写入不会自动清空
 * ⇒ 用「最近一次成功渲染的时间戳」做 500ms 维持窗。
 *
 * <p>显示内容（数据内容对齐 MTR 4.0 的 {@code renderRailStats}，外加用户要的坡度）：
 * <pre>
 *   MTR 4.0 轨道预览
 *   长度 12.345 m
 *   坡度 +2.6% (+1.5°) 上坡
 *   方位 137.5° 东南
 *   偏移 (+12, -3, +5)
 * </pre>
 * 4.0 的 {@code renderRailStats} 三项是「XYZ 偏移 / XZ 半径 / XZ 长度」——
 * 3.x 的 {@code mtr.data.Rail} 没有 {@code getHorizontalRadii()}（javap 实证只有
 * {@code getLength/getPosition/goodRadius/isValid}），拿不到曲率半径，
 * 所以这里用「长度 + 坡度 + 方位 + XYZ 偏移」表达同样的信息。
 */
public final class RailPreviewHud {

	/** 最近一次成功渲染后，HUD 继续显示的时长（毫秒）。只做「防抖」，不做「残留」 */
	private static final long HOLD_MS = 150L;

	private static volatile RailPreviewGeometry.Preview current;
	private static volatile BlockPos crosshair;
	private static volatile BlockPos selected;
	private static volatile boolean existingRail;
	private static volatile long stamp;

	private RailPreviewHud() {
	}

	/** 在客户端初始化里注册（MtrNextClient） */
	public static void register() {
		HudRenderCallback.EVENT.register(RailPreviewHud::onHud);
		mtrnext.MtrNext.LOGGER.info("MTR-Next: rail preview HUD registered (HudRenderCallback)");
	}

	/** 由 RailPreviewRenderer 在每帧成功构造预览后调用 */
	static void update(RailPreviewGeometry.Preview preview, BlockPos crosshairPos, BlockPos selectedPos,
			boolean existing) {
		current = preview;
		crosshair = crosshairPos;
		selected = selectedPos;
		existingRail = existing;
		stamp = System.currentTimeMillis();
	}

	/** 无端点信息的重载已随开发者自检类一并删除（[0.54.0]） */

	/**
	 * 本帧是否有「有效预览」——即最近 150ms 内渲染器成功写入过快照。
	 *
	 * <p>[0.54.0] 抽成公开方法：HUD 与外部判定用同一份语义，避免出现
	 * 「渲染器认为没画 / HUD 认为该画」这种不一致（用户抱怨过的
	 * 「没拿连接器也显示」就是这类不一致）。
	 */
	public static boolean hasActivePreview() {
		final RailPreviewGeometry.Preview p = current;
		return p != null && System.currentTimeMillis() - stamp <= HOLD_MS;
	}

	private static void onHud(GuiGraphics graphics, float tickDelta) {
		final RailPreviewGeometry.Preview p = current;
		// ① 本帧无有效预览（含 150ms 防抖窗过期）⇒ 不画
		if (!hasActivePreview() || p == null) {
			return;
		}
		try {
			final Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.font == null || mc.options.hideGui) {
				return;
			}
			// ★ [0.47.0] 只要有 Screen 打开就绝不画。
			// 原因（真机反馈「编组界面的 GUI 重叠了」）：屏幕打开时世界仍然在渲染，
			// 玩家手里还拿着轨道连接器、准心仍然指向方块 ⇒ 预览数据每帧刷新，
			// HUD 就会叠在 FormationScreen 自己的控件上。
			// 预览面板属于「世界 HUD」，任何界面（编组/物品栏/聊天/暂停）都不该出现。
			if (mc.screen != null) {
				return;
			}
			if (mc.player == null || mc.level == null) {
				return;
			}
			// ★ [0.54.0] 显示条件收紧为「四条全满足，缺一不画」。
			//
			// 历次真机反馈：「没拿连接器的时候也显示」（0.52.0）、「准心指空也在」。
			// 根因是条件分散在渲染器与 HUD 两处、还可能有一处漏判 ⇒ 这里独立复核，
			// 不再依赖「渲染器写过快照」这一件事，也不再有任何开发者旁路
			// （0.53.0 的 {@code RailPreviewSelfTest} 已整类删除）。
			//
			//   ① mc.screen != null            → 任何界面都不画（已在上面判过）
			//   ② !isHoldingRailRelated        → 没拿轨道连接器不画
			//   ③ firstNodeFromHand() == null  → 没选一号节点不画
			//   ④ mc.hitResult 不是 BlockHitResult → 准心没指方块不画
			//   （⑤ hasActivePreview() 已在方法开头判过：本帧无有效预览不画）
			if (!mtr.render.RenderTrains.isHoldingRailRelated(mc.player)) {
				return;
			}
			if (PreviewState.firstNodeFromHand() == null) {
				return;
			}
			if (!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult)) {
				return;
			}

			final String title = existingRail ? "MTR 4.0 轨道预览（该段已有轨道）" : "MTR 4.0 轨道预览";
			final String lineLength = String.format(Locale.ROOT, "长度 %.2f m", p.length);
			final String lineSlope = String.format(Locale.ROOT, "坡度 %s%.2f%% (%s%.2f°) %s",
					sign(p.slopePercent), Math.abs(p.slopePercent),
					sign(p.slopePercent), Math.abs(p.slopeDeg),
					slopeWord(p.slopePercent));
			final String lineHeading = String.format(Locale.ROOT, "方位 %.1f° %s",
					p.headingDeg, directionWord(p.headingDeg));
			final String lineOffset = offsetText();

			final java.util.List<String> lines = new java.util.ArrayList<>(5);
			lines.add(title);
			lines.add(lineLength);
			lines.add(lineSlope);
			lines.add(lineHeading);
			if (lineOffset != null) {
				lines.add(lineOffset);
			}

			final int lineHeight = 10;
			final int padding = 4;
			int textWidth = 0;
			for (final String line : lines) {
				textWidth = Math.max(textWidth, mc.font.width(line));
			}

			final int screenW = mc.getWindow().getGuiScaledWidth();
			final int screenH = mc.getWindow().getGuiScaledHeight();
			final int x = screenW / 2 - textWidth / 2;
			// 准星正下方，避开准星与物品栏
			final int y = screenH / 2 + 14;
			final int boxW = textWidth + padding * 2 + 2;
			final int boxH = lines.size() * lineHeight + padding * 2 - 2;
			final int boxX = x - padding - 2;
			final int boxY = y - padding;

			// 底：半透明深色板（和 75% 半透明的轨道呼应）
			graphics.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xA0101018);
			// 左侧 2px 色条：坡向一眼可辨（平坡绿 / 上坡橙 / 下坡蓝）
			final int accent = slopeAccent(p.slopePercent);
			graphics.fill(boxX, boxY, boxX + 2, boxY + boxH, accent);

			graphics.drawString(mc.font, title, x, y, existingRail ? 0xFFFFC24A : 0xFFE6E6FF);
			graphics.drawString(mc.font, lineLength, x, y + lineHeight, 0xFF55FFFF);
			graphics.drawString(mc.font, lineSlope, x, y + lineHeight * 2, accent);
			graphics.drawString(mc.font, lineHeading, x, y + lineHeight * 3, 0xFFFFD24A);
			if (lineOffset != null) {
				graphics.drawString(mc.font, lineOffset, x, y + lineHeight * 4, 0xFFB9C4D0);
			}
		} catch (final Throwable t) {
			// HUD 出问题绝不能影响游戏渲染
			mtrnext.MtrNext.LOGGER.debug("[preview] hud failed", t);
		}
	}

	/** 4.0 的「XYZ 偏移」：准心方块 − 已选节点（4.0 在两端各画一次，这里只画一次） */
	private static String offsetText() {
		final BlockPos a = crosshair;
		final BlockPos b = selected;
		if (a == null || b == null) {
			return null;
		}
		return String.format(Locale.ROOT, "偏移 (%+d, %+d, %+d)",
				a.getX() - b.getX(), a.getY() - b.getY(), a.getZ() - b.getZ());
	}

	private static String sign(double v) {
		return v > 0.005D ? "+" : v < -0.005D ? "-" : "±";
	}

	/** 坡向配色：平坡绿 / 上坡橙 / 下坡蓝（不做轨道本体上色时的最小可用提示） */
	private static int slopeAccent(double slopePercent) {
		if (slopePercent > 0.5D) {
			return 0xFFFF9A3C;
		}
		if (slopePercent < -0.5D) {
			return 0xFF4FA8FF;
		}
		return 0xFF55FF55;
	}

	private static String slopeWord(double slopePercent) {
		if (slopePercent > 0.5D) {
			return "上坡";
		}
		if (slopePercent < -0.5D) {
			return "下坡";
		}
		return "平坡";
	}

	/** 八方位（每 45° 一个扇区，以正北为中心） */
	private static String directionWord(double headingDeg) {
		final String[] words = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};
		final int idx = (int) Math.round(((headingDeg % 360.0D) + 360.0D) % 360.0D / 45.0D) % 8;
		return words[idx];
	}
}

package mtrnext.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 预览轨道绘制器（3.x 侧轻量实现，语义对齐 4.x 的"手持第二节点看形状"）。
 * <p>
 * 0.1 阶段：直线段折线预览（第一节点→注视点）。
 * 0.2 阶段计划：接入 YMTR Rail 双节点构造器做曲线一致预览。
 * <p>
 * 绘制走 Tesselator 世界空间折线（DEBUG_LINE_STRIP，半透明青色，
 * disableDepthTest 穿墙可见——与 4.x 预览语义一致）。
 */
public final class RailPreviewRenderer {

	private static final int SEGMENTS = 32;
	private static final float PREVIEW_R = 0.3F;
	private static final float PREVIEW_G = 0.9F;
	private static final float PREVIEW_B = 1.0F;
	private static final float PREVIEW_A = 0.6F;

	private RailPreviewRenderer() {
	}

	public static void draw(Minecraft mc, BlockPos first, BlockPos second) {
		final Vec3 start = Vec3.atLowerCornerOf(first);
		final Vec3 end = Vec3.atLowerCornerOf(second);
		if (start.distanceTo(end) < 1) {
			return;
		}

		// [0.3] 曲线精确预览：优先用 YMTR 同源几何采样，失败回退直线
		final Vec3[] curve = RailPreviewGeometry.sampleCurve(mc, first, second);
		final int segments = curve != null ? curve.length - 1 : SEGMENTS;

		final com.mojang.blaze3d.vertex.Tesselator tesselator = com.mojang.blaze3d.vertex.Tesselator.getInstance();
		final com.mojang.blaze3d.vertex.BufferBuilder buffer = tesselator.getBuilder();

		com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
		com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
		com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();

		buffer.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.DEBUG_LINE_STRIP,
				com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
		for (int i = 0; i <= segments; i++) {
			final Vec3 p;
			if (curve != null) {
				p = curve[i];
				if (p == null) {
					continue;
				}
			} else {
				final double t = (double) i / SEGMENTS;
				p = new Vec3(
						start.x + (end.x - start.x) * t,
						start.y + (end.y - start.y) * t + 0.5,
						start.z + (end.z - start.z) * t);
			}
			buffer.vertex(p.x, p.y, p.z)
					.color(PREVIEW_R, PREVIEW_G, PREVIEW_B, PREVIEW_A).endVertex();
		}
		tesselator.end();

		com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
		com.mojang.blaze3d.systems.RenderSystem.disableBlend();
	}
}

package mtrnext.mixin;

import mtrnext.MtrNext;
import mtrnext.client.PreviewState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * [轨道预览 2/2] 预览渲染挂点。
 * <p>
 * YMTR 3.x 的轨道渲染总入口是 {@code RenderTrains.render}（静态, 每帧）。
 * 本 mixin 在其 RETURN 注入：当玩家手持"已选第一节点"的轨道节点物品
 * 且注视着一个方块时，就地画预览轨道。
 * <p>
 * <b>绘制方式（3.x 无 4.x 的 RailWrapper 渲染管线，走轻量自绘）</b>：
 * 用 3.x 自己的 Rail 几何数据源——第一节点与注视点的 BlockPos 差值给出
 * 轨道长度与方向，按 YMTR Rail 的贝塞尔分段采样出折线，以半透明白色
 * 直线段（LevelRenderer 风格的 BufferBuilder LINE_STRIP）绘制。
 * 视觉对齐 4.x 的预览语义：显示将要生成的轨道形状（简化线框）。
 * <p>
 * <b>与 MTR Turbo 的共存</b>：Turbo 的 RenderTrainsMixin 对 render 做
 * @Redirect（NEW HashMap），本 mixin 是 @Inject RETURN——同方法不同注入
 * 类型可共存（与 ANTE 的 @Inject 共存先例同机制），仍需三方实测确认。
 */
@Mixin(targets = "mtr.render.RenderTrains", remap = false)
public abstract class RenderTrainsPreviewMixin {

	@Inject(method = "render(Lnet/minecraft/class_1297;FLnet/minecraft/class_4587;Lnet/minecraft/class_4597;)V", at = @At("RETURN"), remap = false)
	private static void mtrnext$renderRailPreview(Object entity, float tickDelta, Object matrixStack, Object vertexConsumerProvider, CallbackInfo ci) {
		try {
			final Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || mc.level == null || mc.hitResult == null) {
				return;
			}
			if (!(mc.hitResult instanceof BlockHitResult)) {
				return;
			}
			if (!PreviewState.hasFirstNode()) {
				return;
			}
			final BlockPos first = PreviewState.getFirstNode();
			final BlockPos second = ((BlockHitResult) mc.hitResult).getBlockPos();
			if (first.equals(second)) {
				return;
			}
			mtrnext.client.RailPreviewRenderer.draw(mc, first, second);
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("rail preview render failed", t);
		}
	}
}

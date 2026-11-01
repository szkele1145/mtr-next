package mtrnext.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 轨道预览渲染挂点。
 *
 * <p><b>★ [0.52.0] 定稿：挂点不变（MTR 渲染末尾），几何交回 YMTR 原生渲染器</b>
 *
 * <p>历史教训（真机逐条踩过，别再试）：
 * <ul>
 *   <li>{@code WorldRenderEvents.LAST} + {@code MultiBufferSource.immediate(Tesselator...)}
 *       ⇒ flush 掉 MC 自己的批次 ⇒ <b>整个世界渲染异常（车辆方块全部消失）</b>；</li>
 *   <li>本方法的 RETURN 里用 {@code Tesselator.getInstance().getBuilder()} + {@code ts.end()}
 *       ⇒ 打断 MTR 尚未提交的排队批次 ⇒ <b>轨道渲染错位漂移 / 顶点增生</b>；</li>
 *   <li>{@code ctx.matrixStack()} / 任何 {@code Matrix4f} 二次变换 ⇒ 预览跑到世界底部之外；</li>
 *   <li>{@code MultiBufferSource} + 自绘 {@code RenderType} 虽然能避开上面三条，
 *       但用户最终不要「准心指哪都预览」那套交互 ⇒ 整体回退。</li>
 * </ul>
 *
 * <p>现在只做一件事：在 MTR 渲染完所有内容后调用
 * {@link mtrnext.client.RailPreviewRenderer#draw}，
 * 由它判定「手持连接器 + 已选一号节点 + 准心指向 5 格内的二号节点」，
 * 再交给 {@code renderRailStandardSafe(level, rail, 0.065625f, true, 1.0f)} 画
 * —— 这条路用户确认过「轨道渲染正常」。
 */
@Mixin(value = mtr.render.RenderTrains.class, remap = false)
public abstract class RenderTrainsPreviewMixin {

	@Inject(
			method = "render(Lmtr/entity/EntitySeat;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
			at = @At("RETURN"),
			remap = true,
			require = 0
	)
	private static void mtrnext$renderRailPreview(mtr.entity.EntitySeat entity, float tickDelta,
			com.mojang.blaze3d.vertex.PoseStack matrixStack,
			net.minecraft.client.renderer.MultiBufferSource vertexConsumerProvider, CallbackInfo ci) {
		try {
			mtrnext.client.RailPreviewRenderer.draw(Minecraft.getInstance());
		} catch (final Throwable t) {
			mtrnext.MtrNext.LOGGER.debug("rail preview hook failed", t);
		}
	}
}

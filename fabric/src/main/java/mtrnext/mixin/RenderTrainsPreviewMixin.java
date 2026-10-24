package mtrnext.mixin;

import mtrnext.MtrNext;
import mtrnext.client.PreviewState;
import mtr.entity.EntitySeat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
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
 * <p>
 * [0.35.1] 目标类改用类字面量 {@code value = mtr.render.RenderTrains.class}
 * （原 {@code targets = "..."} 字符串）。运行时等价，但 targets 字符串目标下
 * AP 不解析目标类——注入点写错也编译通过、运行时静默不命中（0.34.2 修的
 * 正是这类"永不命中"）。改用类引用后 AP 真正解析 YMTR 目标类并校验注入点，
 * 把静默失效提前成编译错误。目标类经 javap 实证为 public class。
 */
@Mixin(value = mtr.render.RenderTrains.class, remap = false)
public abstract class RenderTrainsPreviewMixin {

	// [0.34.2 修复·完整链] 三层证据：
	// 1) 运行时真身（生产 jar javap 实证）：static render 签名是
	//    (Lmtr/entity/EntitySeat;FLnet/minecraft/class_4587;Lnet/minecraft/class_4597;)V
	//    —— 第一参数是 YMTR 自己的 EntitySeat，不是原版 MTR 的 class_1297
	//    （Turbo 面向原版 MTR，宿主签名不同，不能照抄）。
	//    旧代码写 class_1297 且 remap=false 字面匹配 → 永不命中，预览自加入起从未生效。
	// 2) 编译期：本工程用 officialMojangMappings，Loom 把 YMTR 重映射成
	//    Mojmap 名进 classpath（javap 实证：PoseStack/MultiBufferSource）。
	//    类级 remap=false 会被成员继承，成员也字面匹配 → AP 在 Mojmap
	//    classpath 找 class_4587 必报 "Cannot find target method"。
	// 3) 桥接：成员级显式 remap=true + Mojmap 描述符。AP 借 mappings 在
	//    编译期强校验签名（写错立刻发现），并把 PoseStack→class_4587 写入
	//    mtr-next.refmap.json；运行时 mixin 系统经 refmap 还原成 intermediary
	//    描述符，与生产 YMTR 字面签名精确命中。EntitySeat 是 YMTR 自有类，
	//    非 net/minecraft 前缀不被重映射，两空间一致，无桥接风险。
	//
	// ★ [0.38.1 真机修复] handler 参数**不能**用 Object 占位。
	//   0.36.7 在真实客户端（212 mod）启动即崩：
	//     InvalidInjectionException: Invalid descriptor on mtr-next.mixins.json:RenderTrainsPreviewMixin
	//       Expected (Lmtr/entity/EntitySeat;FLnet/minecraft/class_4587;Lnet/minecraft/class_4597;CallbackInfo;)V
	//       but found (Ljava/lang/Object;FLjava/lang/Object;Ljava/lang/Object;CallbackInfo;)V
	//   机理：Mixin 校验 handler 的**实际字节码描述符**与 target 描述符是否**逐字相等**。
	//   remap=true 会把 target 描述符经 refmap 还原成 intermediary；handler 描述符虽也
	//   被 remap，但 java.lang.Object **不在 mappings 里** ⇒ remap 后仍是 Ljava/lang/Object;
	//   ⇒ 永不匹配。handler 参数必须写**精确类型**（EntitySeat / PoseStack /
	//   MultiBufferSource），让 refmap 把它们映射成 intermediary 名。
	//   ★ 为什么静态审计查不出来：字节码审计只看「引用的成员是否存在」，而
	//   (Object,float,Object,Object,CallbackInfo) 本身是**合法**字节码 —— 只有真正
	//   跑一次 Mixin 变换才会暴露描述符不匹配。
	//   ⇒ 任何 mixin 改动后必须真机 runClient 验证。
	@Inject(method = "render(Lmtr/entity/EntitySeat;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V", at = @At("RETURN"), remap = true)
	private static void mtrnext$renderRailPreview(EntitySeat entity, float tickDelta, PoseStack matrixStack, MultiBufferSource vertexConsumerProvider, CallbackInfo ci) {
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

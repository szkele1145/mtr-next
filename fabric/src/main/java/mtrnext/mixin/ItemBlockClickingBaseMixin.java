package mtrnext.mixin;

import mtrnext.MtrNext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * [轨道预览 1/2] 第一节点状态捕获。
 * <p>
 * 实证（javap mtr.item.ItemBlockClickingBase）：
 * 第一节点存在<b>物品 NBT</b> 的 {@code pos} 键（TAG_POS 常量，ldc 可见）——
 * {@code use()} 内读 {@code stack.getNbt().contains("pos")} 判断是否已选点，
 * 命中则取 NBT long 走 onEndClick 完成连接并清除。
 * <p>
 * 本 mixin 在 use() 返回后把"手持物品 NBT 里的第一节点"快照到
 * {@link mtrnext.client.PreviewState}（客户端可读的全局），供预览渲染层
 * 每帧取用。双端共用同一段逻辑（服务端也快照，便于将来预测校验），
 * 但渲染只读客户端实例。
 * <p>
 * 与 4.x 的差异：4.x 把状态放 ItemNodeModifierBase 静态字段+MinecraftClientData，
 * 3.x 走 NBT（更稳——切手持/掉落不丢）。我们直接镜像 3.x 的 NBT 约定，
 * 不需要自己存状态，只做"旁路快照"。
 * <p>
 * [0.35.1] 目标类改用类字面量 {@code value = mtr.item.ItemBlockClickingBase.class}
 * （原 {@code targets = "..."} 字符串）。运行时等价，但 targets 字符串目标下
 * AP 不解析目标类——注入点写错也编译通过、运行时静默不命中。改用类引用后
 * AP 真正解析 YMTR 目标类并校验注入点，把静默失效提前成编译错误。
 * 目标类经 javap 实证为 public class。
 */
@Mixin(value = mtr.item.ItemBlockClickingBase.class, remap = false)
public abstract class ItemBlockClickingBaseMixin {

	/**
	 * javap 实证（YMTR 3.6.4.2 发布 jar）：intermediary 名 method_7884=use。
	 * remap=false + targets 字符串环境下 method 用 intermediary 名。
	 */
	@Inject(method = "method_7884", at = @At("RETURN"))
	private void mtrnext$snapshotFirstPos(net.minecraft.world.item.context.UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
		try {
			final ItemStack stack = context.getItemInHand();
			final CompoundTag nbt = stack.getTag();
			if (nbt != null && nbt.contains("pos")) {
				mtrnext.client.PreviewState.setFirstNodePos(nbt.getLong("pos"));
			} else {
				mtrnext.client.PreviewState.setFirstNodePos(null);
			}
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("preview snapshot failed", t);
		}
	}
}

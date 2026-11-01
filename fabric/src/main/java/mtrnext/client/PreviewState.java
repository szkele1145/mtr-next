package mtrnext.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 预览渲染层的全局状态。
 *
 * <p><b>★ [0.38.2 真机修复] 改为「渲染时直接读手持物品 NBT」，不再依赖 mixin 快照。</b>
 *
 * <p><b>原设计的致命缺陷</b>：旧实现靠 {@code ItemBlockClickingBaseMixin} 注入
 * {@code ItemBlockClickingBase.method_7884}（即 {@code use}）的 {@code RETURN}，
 * 把物品 NBT 里的 {@code pos} 快照进本类。但 YMTR 的 {@code use} 字节码是：
 * <pre>
 *   if (!world.isClient()) {        // ★ 服务端才走 NBT 逻辑
 *       ... 读写 NBT 的 pos ...
 *       return SUCCESS;
 *   }
 *   return super.use(context);      // 客户端直接走父类，完全不碰 NBT
 * </pre>
 * ⇒ 客户端 {@code use()} 返回时 NBT 里<b>还没有</b> {@code pos}；
 * 服务端写好后经物品同步回客户端，但<b>不会再次调用 use()</b>。
 * ⇒ 旧实现的 {@code hasFirst} 永远是 false ⇒ <b>预览从未显示过</b>。
 *
 * <p><b>新设计</b>：预览本身每帧都在渲染线程跑（见 {@code RenderTrainsPreviewMixin}），
 * 那时的玩家对象、手持物品、NBT 都是最新的 ⇒ 直接在那里读，不需要任何快照。
 */
public final class PreviewState {

	private static volatile BlockPos secondNodePos = null;

	private PreviewState() {
	}

	/**
	 * 当前手持物品里记录的第一节点（{@code TAG_POS = "pos"}）。
	 *
	 * @return 未选中任何节点时返回 null
	 */
	public static BlockPos firstNodeFromHand() {
		final Minecraft mc = Minecraft.getInstance();
		final Player player = mc == null ? null : mc.player;
		if (player == null) {
			return null;
		}
		final ItemStack stack = player.getMainHandItem();
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		final CompoundTag nbt = stack.getTag();
		if (nbt == null || !nbt.contains("pos")) {
			return null;
		}
		return BlockPos.of(nbt.getLong("pos"));
	}

	/** 是否已选中第一节点（渲染时的实时判定） */
	public static boolean hasFirstNode() {
		return firstNodeFromHand() != null;
	}

	/** 第一节点（渲染时的实时判定；未选中返回 null） */
	public static BlockPos getFirstNode() {
		return firstNodeFromHand();
	}

	public static void setSecondNode(BlockPos pos) {
		secondNodePos = pos;
	}

	public static BlockPos getSecondNode() {
		return secondNodePos;
	}

	/**
	 * @deprecated 客户端读不到服务端写入的 NBT，旧快照路径不可用；
	 *             请用 {@link #firstNodeFromHand()}。保留仅为二进制兼容。
	 */
	@Deprecated
	public static void setFirstNodePos(Long packed) {
		// no-op：状态由 firstNodeFromHand() 实时读取
	}
}

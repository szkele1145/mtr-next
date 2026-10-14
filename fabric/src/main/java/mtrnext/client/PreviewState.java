package mtrnext.client;

import net.minecraft.core.BlockPos;

/**
 * 预览渲染层的全局状态（客户端单例语义）。
 * <ul>
 *   <li>{@code firstNodePos}——手持物品 NBT 旁路快照来的"已选第一节点"
 *       （null=未选）；</li>
 *   <li>{@code secondNodePos}——玩家当前注视的方块（每帧由预览渲染层
 *       用 HitResult 更新）。</li>
 * </ul>
 * 两者齐备时渲染层画预览轨道。
 */
public final class PreviewState {

	private static volatile long firstNodePos = 0;
	private static volatile boolean hasFirst = false;
	private static volatile BlockPos secondNodePos = null;

	private PreviewState() {
	}

	public static void setFirstNodePos(Long packed) {
		if (packed == null) {
			hasFirst = false;
			firstNodePos = 0;
		} else {
			hasFirst = true;
			firstNodePos = packed;
		}
	}

	public static boolean hasFirstNode() {
		return hasFirst;
	}

	public static BlockPos getFirstNode() {
		return BlockPos.of(firstNodePos);
	}

	public static void setSecondNode(BlockPos pos) {
		secondNodePos = pos;
	}

	public static BlockPos getSecondNode() {
		return secondNodePos;
	}
}

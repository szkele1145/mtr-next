package mtrnext.mixin;

import mtr.data.Rail;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * [0.39.3] 暴露 YMTR 自己的轨道渲染器（{@code RenderTrains.renderRailStandardSafe}）。
 *
 * <p><b>为什么需要 9 参版本</b>（字节码实证）：
 * 5 参 {@code renderRailStandardSafe(Level, Rail, float, boolean, float)} 内部是：
 * <pre>
 *   previewFlag &amp;&amp; rail.railType == QUARTZ
 *       ? "mtr:textures/block/rail_preview.png"   // 有 mtr: 前缀
 *       : "textures/block/rail.png"               // ★ 缺 mtr: 前缀
 * </pre>
 * ⇒ 非预览模式那条分支的贴图路径会被解析成
 * {@code minecraft:textures/block/rail.png}（不存在）⇒ <b>渲染不出东西</b>
 * （真机实测：改用 5 参 + previewFlag=false 后预览直接消失）。
 *
 * <p>所以改用 9 参版本，由调用方显式给出带命名空间的贴图路径。
 */
@Mixin(value = mtr.render.RenderTrains.class, remap = false)
public interface RenderTrainsRailInvoker {

	/**
	 * 5 参版本（预览贴图分支可用，贴图为 rail_preview.png）。
	 *
	 * <p>★ [0.44.1] <b>参数名修正</b>：第 5 个参数是 <b>{@code railWidth}</b>，
	 * 不是 {@code depth}。反编译实证：
	 * <pre>
	 *   private static void renderRailStandardSafe(
	 *       Level world, Rail rail, float yOffset, boolean renderColors, float railWidth)
	 * </pre>
	 * 它最终传给 {@code Rail.render(callback, -railWidth, +railWidth)}
	 * （{@code mtr/data/Rail.class:442}）⇒ <b>传 0 会得到宽度为 0 的退化四边形</b>，
	 * 也就是真机上「预览只有一条细线」。原生真实轨道调用时传的是 {@code 1.0f}。
	 *
	 * @param yOffset      抬高量（格）。原生真实轨道用 {@code 0.065625f}
	 * @param previewFlag  true 且 railType==QUARTZ 时用 {@code mtr:textures/block/rail_preview.png}
	 * @param railWidth    轨道半宽（格）。{@code 1.0f} ⇒ 总宽 2 格
	 */
	@Invoker("renderRailStandardSafe")
	static void mtrnext$renderRailSafe5(Level world, Rail rail, float yOffset, boolean previewFlag, float railWidth) {
		throw new AssertionError();
	}

	/**
	 * 9 参版本：可显式指定贴图路径。
	 *
	 * @param yOffset     抬高量（格）
	 * @param previewFlag 预览标记（决定 color 是否参与）
	 * @param railWidth   轨道半宽（格），<b>不是深度</b>
	 * @param texture     贴图路径，必须带命名空间（如 {@code mtr:textures/block/rail.png}）
	 */
	@Invoker("renderRailStandardSafe")
	static void mtrnext$renderRailSafe9(Level world, Rail rail, float yOffset, boolean previewFlag, float railWidth,
			String texture, float f1, float f2, float f3, float f4) {
		throw new AssertionError();
	}
}

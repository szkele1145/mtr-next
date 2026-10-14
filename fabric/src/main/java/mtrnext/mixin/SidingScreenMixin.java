package mtrnext.mixin;

import mtrnext.MtrNext;
import mtrnext.pack.VehiclePackLoader;
import mtrnext.screen.FormationScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * [编组 2/2] 编组屏入口注入。
 * <p>
 * 在 YMTR 的 SidingScreen.init() 尾部追加"编组"按钮——
 * 仅当 4.0 车包存在时显示；3.2-only 用户界面零变化。
 * <p>
 * mixin 宿主是 SavedRailScreenBase<Siding> 的子类，本身继承 Screen——
 * 本 mixin 继承 Screen 以获得 addRenderableWidget/width/height 的可调用形态
 * （mixin 合并语义：这些成员在目标类运行期由宿主链提供）。
 * 构造器仅满足 javac 的继承语法，mixin 框架不会调用它。
 * <p>
 * Siding 持久键：0.1 用界面实例哈希临时键；
 * 0.2 计划经宿主泛型字段（javap 见 SavedRailScreenBase 的宿主 data 字段）
 * 精确取 Siding.id。
 */
@Mixin(targets = "mtr.screen.SidingScreen", remap = false)
public abstract class SidingScreenMixin extends Screen {

	protected SidingScreenMixin(net.minecraft.network.chat.Component title) {
		super(title);
	}

	@Inject(method = "method_25426", at = @At("RETURN"))
	private void mtrnext$addFormationButton(CallbackInfo ci) {
		try {
			if (!VehiclePackLoader.has4xVehicles()) {
				return; // 3.2-only: 零 UI 变化
			}
			final java.util.UUID sidingKey = java.util.UUID.nameUUIDFromBytes(
					("mtrnext-screen-" + System.identityHashCode(this)).getBytes());
			addRenderableWidget(Button.builder(
							net.minecraft.network.chat.Component.literal("编组 [MTR-Next]"),
							b -> FormationScreen.open(sidingKey))
					.bounds(this.width - 120, 8, 110, 18).build());
			MtrNext.LOGGER.info("Formation button added to SidingScreen");
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("formation button inject failed", t);
		}
	}
}

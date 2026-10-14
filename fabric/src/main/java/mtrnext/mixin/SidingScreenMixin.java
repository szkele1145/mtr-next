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
 * <b>Siding 持久键（javap 实证链）</b>：
 * SidingScreen → SavedRailScreenBase<Siding>（protected final T savedRailBase）
 * → SavedRailBase → NameColorDataBase（**public final long id**）。
 * mixin 经宿主的 savedRailBase 字段转型 mtr.data.Siding 后直接读 .id
 * （public 字段无需 accessor）——编组存储按这个长期 id 键控，
 * 界面重开/重启世界均稳定。
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
			// 持久键：宿主 SavedRailScreenBase.savedRailBase 的 id（long, public final）
			final long sidingId = mtrnext$hostSidingId();
			if (sidingId == 0) {
				return;
			}
			// [0.4] 宿主 Siding 实例传递（编组屏关闭时桥接车辆配置）
			mtrnext.util.HostSidingHolder.set(mtrnext$hostSiding());
			final java.util.UUID key = new java.util.UUID(0, sidingId);
			addRenderableWidget(Button.builder(
							net.minecraft.network.chat.Component.literal("编组 [MTR-Next]"),
							b -> FormationScreen.open(key))
					.bounds(this.width - 120, 8, 110, 18).build());
			MtrNext.LOGGER.info("Formation button added to SidingScreen (siding id {})", sidingId);
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("formation button inject failed", t);
		}
	}

	/** 取宿主 Siding 实例（供编组桥接） */
	private Object mtrnext$hostSiding() {
		try {
			final java.lang.reflect.Field f = mtr.screen.SavedRailScreenBase.class.getDeclaredField("savedRailBase");
			f.setAccessible(true);
			return f.get(this);
		} catch (final Throwable t) {
			return null;
		}
	}

	/** 从宿主实例取 Siding 持久 id——经 SavedRailScreenBase 的 protected savedRailBase 字段 */
	private long mtrnext$hostSidingId() {
		try {
			final java.lang.reflect.Field f = mtr.screen.SavedRailScreenBase.class.getDeclaredField("savedRailBase");
			f.setAccessible(true);
			final Object savedRail = f.get(this);
			if (savedRail instanceof mtr.data.Siding siding) {
				return siding.id;
			}
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("host siding id lookup failed", t);
		}
		return 0;
	}
}

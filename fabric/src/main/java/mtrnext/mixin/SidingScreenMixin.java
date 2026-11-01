package mtrnext.mixin;

import mtrnext.MtrNext;
import mtrnext.pack.VehiclePackLoader;
import mtrnext.screen.FormationScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
 * <p>
 * [0.35.1] 目标类改用类字面量 {@code value = mtr.screen.SidingScreen.class}
 * （原 {@code targets = "..."} 字符串）。运行时等价，但 targets 字符串目标下
 * AP 不解析目标类——注入点写错也编译通过、运行时静默不命中。改用类引用后
 * AP 真正解析 YMTR 目标类并校验注入点，把静默失效提前成编译错误。
 * 目标类经 javap 实证为 public class。
 */
@Mixin(value = mtr.screen.SidingScreen.class, remap = false)
public abstract class SidingScreenMixin extends Screen {

	protected SidingScreenMixin(net.minecraft.network.chat.Component title) {
		super(title);
	}

	/** 宿主私有方法：收起「选择车型」列表。mixin 可直接 shadow 调用。 */
	@Shadow
	private void setIsSelectingTrain(boolean isSelectingTrain) {
		throw new AssertionError("shadow");
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

	// ==================================================================
	// [0.59.0] 「选了 4.0 车却没反应」——配车界面的静默丢弃
	// ==================================================================

	/**
	 * YMTR 原逻辑（CFR 反编译 {@code mtr/screen/SidingScreen.java:299-305}）：
	 * <pre>
	 *   private void onAdd(NameColorDataBase data, int index) {
	 *       if (data instanceof TrainForList
	 *               &amp;&amp; savedRailBase.isValidVehicle(TrainType.getSpacing(properties.baseTrainType))) {
	 *           savedRailBase.setTrainIdAndBaseType(...);
	 *           this.setIsSelectingTrain(false);
	 *       }
	 *       // ★ else 什么都不做：点击被静默丢弃，界面不关、车型不变、无任何提示
	 *   }
	 * </pre>
	 *
	 * <p><b>为什么这是「强制兼容」的另一半</b>：4.0 车的车长（12 格 ⇒ spacing 13）
	 * 比短侧线大时，{@code isValidVehicle} 为 {@code false}，玩家在这个界面上
	 * <b>永远选不上 4.0 车型</b>——点一下没反应，只能回头去点原版车。
	 * 从玩家视角看，就是「它不让我用 4.0 车，只能换成原版」。
	 *
	 * <p>本注入把这条静默丢弃改成：<b>4.0 车型一律接受</b>（放不下由 MTR 按
	 * {@code floor(railLength / spacing)} 自己截断车厢数，而不是替玩家换车型）。
	 * 3.x 车型<b>行为逐字不变</b>（继续走上游判断）。
	 */
	@Inject(method = "onAdd", at = @At("HEAD"), cancellable = true, remap = false)
	private void mtrnext$accept4xSelection(mtr.data.NameColorDataBase data, int index, CallbackInfo ci) {
		try {
			final Object host = mtrnext$hostSiding();
			if (!(host instanceof mtr.data.Siding siding)) {
				return;
			}
			final mtr.client.TrainProperties props = mtrnext$trainPropertiesOf(data);
			if (props == null || props.baseTrainType == null) {
				return;
			}
			// 只接管 4.0 车型；3.x 车型原样交回上游
			if (mtrnext.pack.VehicleLookup.find(props.baseTrainType) == null) {
				return;
			}
			final String trainId = mtrnext$stringFieldOf(data, "trainId");
			final int spacing = mtr.data.TrainType.getSpacing(props.baseTrainType);
			final boolean fits = siding.isValidVehicle(spacing);
			MtrNext.LOGGER.info("[compat-audit] 配车界面选中 4.0 车型 trainId='{}' base='{}' spacing={} "
							+ "railLength={} 放得下={}",
					trainId, props.baseTrainType, spacing, siding.railLength, fits);
			if (fits) {
				MtrNext.LOGGER.info("[compat-audit] 放得下 → 交回上游原逻辑（正常写入并收起列表）");
				return;
			}
			// ★ 放不下也不丢弃：按玩家选择写入，绝不替他换成内置车
			siding.setTrainIdAndBaseType(trainId, props.baseTrainType,
					packet -> mtr.packet.PacketTrainDataGuiClient.sendUpdate(
							mtr.packet.IPacket.PACKET_UPDATE_SIDING, packet));
			this.setIsSelectingTrain(false);
			ci.cancel();
			MtrNext.LOGGER.warn("[compat-audit] 侧线放不下（spacing={} > {}），仍按玩家选择写入 "
							+ "trainId='{}' base='{}'（上游原本会静默丢弃这次点击）",
					spacing, Math.max(2.0F, siding.railLength), siding.getTrainId(), siding.getBaseTrainType());
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("onAdd 4.0 选择接管失败（交回上游）", t);
		}
	}

	/** {@code SidingScreen.TrainForList.trainProperties}（私有内部类字段，反射取） */
	private static mtr.client.TrainProperties mtrnext$trainPropertiesOf(Object data) {
		if (data == null) {
			return null;
		}
		try {
			final java.lang.reflect.Field f = data.getClass().getDeclaredField("trainProperties");
			f.setAccessible(true);
			final Object v = f.get(data);
			return v instanceof mtr.client.TrainProperties p ? p : null;
		} catch (final Throwable t) {
			return null;
		}
	}

	private static String mtrnext$stringFieldOf(Object data, String name) {
		if (data == null) {
			return null;
		}
		try {
			final java.lang.reflect.Field f = data.getClass().getDeclaredField(name);
			f.setAccessible(true);
			final Object v = f.get(data);
			return v instanceof String s ? s : null;
		} catch (final Throwable t) {
			return null;
		}
	}
}

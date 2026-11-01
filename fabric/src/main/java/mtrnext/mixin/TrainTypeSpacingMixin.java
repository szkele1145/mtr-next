package mtrnext.mixin;

import mtr.data.TrainType;
import mtr.data.TransportMode;
import mtrnext.MtrNext;
import mtrnext.pack.VehicleLookup;
import mtrnext.pack.VehicleResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;

/**
 * <b>[0.59.0] 让 YMTR 的车型几何解析认识「4.0 车型 id」。</b>
 *
 * <h2>这是「彻底铲除强制兼容」的必要条件，不是妥协</h2>
 * 在此之前，把 4.0 车型（{@code eridu-dg1000a}）写进
 * {@code Siding.baseTrainType} 是<b>做不到</b>的：
 * <pre>
 *   // mtr/data/TrainType.java:155-177（CFR 反编译，逐字）
 *   private static void splitTrainType(String trainType, TrainTypeCallback cb) {
 *       for (TransportMode m : TransportMode.values()) {
 *           if (trainType.startsWith(m + "_")) { ...解析 len/width...; return; }
 *       }
 *       for (TrainType t : TrainType.values()) {
 *           if (trainType.equalsIgnoreCase(t.toString())) { 递归; return; }
 *       }
 *       // ★ 两条都不命中 ⇒ 回调根本不调用 ⇒ getSpacing 返回 1+1 = 2
 *   }
 * </pre>
 * 于是 {@code getSpacing("eridu-dg1000a") == 2}：一节 12 格长的车被当成 2 格，
 * {@code trainCars = floor(railLength / 2)} 会放出十倍的车厢，渲染间距全错。
 *
 * <p>正因为如此，旧实现才把 4.0 车型<b>翻译</b>成 {@code train_12_3} 再写进
 * {@code baseTrainType}——那层翻译就是用户口中的「强制兼容」，也是
 * {@code [autoadapt] ... base='train_12_3'} 这行日志的来源。
 *
 * <p>本 mixin 在 {@code train_12_3} 的能力边界上补一条分支：
 * <b>4.0 车型 id 直接给出真实长度/宽度/交通方式</b>。补上之后，
 * 「翻译成 3.x 车型」这层就<b>没有任何存在理由</b>，由
 * {@link SidingAutoAdaptMixin} 一并删除。
 *
 * <h2>为什么是 HEAD + cancel，而不是改返回值</h2>
 * 原方法对每个参数都要遍历 {@code TransportMode.values()} 与
 * {@code TrainType.values()}（近百项）。4.0 车型的答案在
 * {@link VehicleLookup} 里是 O(1)，直接短路更省。
 * <b>不命中时一律放行</b>（{@link VehicleLookup#find} 返回 {@code null}），
 * 3.x 行为逐字不变。
 *
 * <h2>数值口径</h2>
 * <ul>
 *   <li>{@code getSpacing} = {@code round(length) + 1} —— 与 YMTR
 *       {@code splitTrainType} 的 {@code getSpacing = length + 1} 一致；</li>
 *   <li>{@code getWidth} = {@code round(width)}；</li>
 *   <li>{@code getTransportMode} = 车包的 {@code transportMode} 字段
 *       （{@code "TRAIN"} / {@code "BOAT"} / {@code "CABLE_CAR"} / {@code "AIRPLANE"}）。</li>
 * </ul>
 */
@Mixin(value = mtr.data.TrainType.class, remap = false)
public abstract class TrainTypeSpacingMixin {

	/** 只在首次命中时打印，避免热路径刷屏 */
	private static final java.util.Set<String> LOGGED =
			java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

	@Inject(method = "getSpacing", at = @At("HEAD"), cancellable = true, remap = false)
	private static void mtrnext$spacingOf4x(String trainType, CallbackInfoReturnable<Integer> cir) {
		final VehicleResource res = VehicleLookup.find(trainType);
		if (res == null) {
			return; // 3.x 字符串：原逻辑逐字生效
		}
		final int len = Math.max(1, (int) Math.round(res.length));
		final int spacing = len + 1;
		if (LOGGED.add("spacing:" + trainType)) {
			MtrNext.LOGGER.info("[compat-audit] TrainType.getSpacing('{}') → {}（4.0 车包真实车长 {}，不再退化为 2）",
					trainType, spacing, res.length);
		}
		cir.setReturnValue(spacing);
	}

	@Inject(method = "getWidth", at = @At("HEAD"), cancellable = true, remap = false)
	private static void mtrnext$widthOf4x(String trainType, CallbackInfoReturnable<Integer> cir) {
		final VehicleResource res = VehicleLookup.find(trainType);
		if (res == null) {
			return;
		}
		final int width = Math.max(1, (int) Math.round(res.width));
		if (LOGGED.add("width:" + trainType)) {
			MtrNext.LOGGER.info("[compat-audit] TrainType.getWidth('{}') → {}（4.0 车包真实车宽 {}）",
					trainType, width, res.width);
		}
		cir.setReturnValue(width);
	}

	@Inject(method = "getTransportMode", at = @At("HEAD"), cancellable = true, remap = false)
	private static void mtrnext$modeOf4x(String trainType, CallbackInfoReturnable<TransportMode> cir) {
		final VehicleResource res = VehicleLookup.find(trainType);
		if (res == null) {
			return;
		}
		final TransportMode mode = mtrnext$parseMode(res.transportMode);
		if (mode == null) {
			return; // 车包写了非法值：交给原逻辑（默认 TRAIN），不要炸
		}
		if (LOGGED.add("mode:" + trainType)) {
			MtrNext.LOGGER.info("[compat-audit] TrainType.getTransportMode('{}') → {}（4.0 车包字段 {}）",
					trainType, mode, res.transportMode);
		}
		cir.setReturnValue(mode);
	}

	private static TransportMode mtrnext$parseMode(String name) {
		if (name == null || name.isEmpty()) {
			return null;
		}
		try {
			return TransportMode.valueOf(name.trim().toUpperCase(Locale.ENGLISH));
		} catch (final Throwable t) {
			return null;
		}
	}
}

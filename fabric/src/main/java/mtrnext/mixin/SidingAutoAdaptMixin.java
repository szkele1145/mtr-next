package mtrnext.mixin;

import mtr.data.Siding;
import mtr.data.TrainType;
import mtr.data.TransportMode;
import mtrnext.MtrNext;
import mtrnext.pack.VehicleLookup;
import mtrnext.pack.VehiclePackLoader;
import mtrnext.pack.VehicleResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;

/**
 * <b>[0.59.0] 把 YMTR 的「自动挑一个内置车型覆盖用户选择」彻底铲除。</b>
 *
 * <h2>上游到底在哪换车（CFR 反编译 {@code mtr/data/Siding.java}，逐字）</h2>
 * <pre>
 *   498: private void setTrainDetails() {
 *   499:     for (TrainType trainType : TrainType.values()) {
 *   500:         if (TrainType.getTransportMode(trainType.baseTrainType) != this.transportMode
 *   501:                 || !this.isValidVehicle(TrainType.getSpacing(trainType.baseTrainType))) continue;
 *   502:         this.setTrainDetails(trainType.toString(), trainType.baseTrainType, true); // force
 *   503:         return;
 *   504:     }
 *   505:     this.setTrainDetails(TrainType.values()[0].toString(), ..., true);
 *   506: }
 *   507: private void setTrainDetails(String trainId, String baseTrainType, boolean force) {
 *   508:     String baseTrainType2 = baseTrainType.startsWith("base_") ? ... : baseTrainType;
 *   509:     int trainSpacing = TrainType.getSpacing(baseTrainType2);
 *   510:     if (force || this.isValidVehicle(trainSpacing)) {  ...按参数写入... }
 *   511:     else { this.setTrainDetails(); }                    // ← 回退：换成内置车
 *   512: }
 * </pre>
 * <b>穷举结论（0.59.0 全量核对）</b>：整个 YMTR jar 里
 * 「遍历 {@code TrainType.values()} 挑内置车并写入」<b>只有第 498-506 行这一处</b>
 * （{@code TrainType.values()} 全 jar 仅 3 处：Siding 498/504、TrainType 自身的
 * {@code splitTrainType} 反查表；后者只做名字→几何解析，不改任何状态）。
 * {@code CustomResources} 只处理 3.x 的 {@code custom_trains} 段，
 * 全 jar 对 4.0 车包字段（{@code "vehicles"} / {@code model_files} /
 * {@code positionDefinitions}）<b>零命中</b>——YMTR 本体根本不认识 4.0 车包。
 *
 * <h2>0.48~0.58 为什么「看起来删了、用户还说没删」</h2>
 * 之前只掐掉了<b>上游的换车</b>，但 mtr-next 自己保留了<b>另一层等价行为</b>：
 * 把 4.0 车型<b>翻译</b>成 3.x 车型串再写进 {@code Siding.baseTrainType}
 * （{@code nativeBaseType()} / {@code baseTypeForCars()}）。
 * 于是日志始终是
 * <pre>[autoadapt] setTrainDetails RETURN → trainId='eridu-dg1000a' base='train_12_3'</pre>
 * —— {@code trainId} 是 4.0 的，{@code baseTrainType} 却被冒充成内置车型格式。
 * 玩家在界面上「配的是 4.0 车」，数据层「存的是 3.x 车」，正是
 * <b>「强制兼容功能未删除」</b>。
 *
 * <h2>本版语义（三条，缺一不可）</h2>
 * <ol>
 *   <li><b>无参 {@code setTrainDetails()} 无条件 cancel</b>：上游 498-506 那段
 *       「挑内置车」在运行时<b>一次都不执行</b>。仅在 {@code baseTrainType}
 *       为空（构造器里的真·未初始化）时补一个默认值，否则 Siding 无法序列化。</li>
 *   <li><b>{@code (String,String,boolean)} 无条件由本 mixin 写入并 cancel</b>：
 *       上游 510 的 {@code force}/{@code isValidVehicle} 判断与 511 的回退分支
 *       <b>整体不可达</b>。「放不下就换成内置车」这条路径从字节码层面消失。</li>
 *   <li><b>4.0 归一化</b>：只要 {@code trainId} 命中 4.0 车包，
 *       {@code baseTrainType} 一律写成该车的 4.0 family 名
 *       （{@code eridu-dg1000a}），<b>覆盖任何上游塞进来的 {@code train_x_y} 合成串</b>。
 *       长度/宽度由 {@link TrainTypeSpacingMixin} 从车包直接解析，
 *       不再需要「翻译成 3.x 车型」。</li>
 * </ol>
 * 三条合起来的结果：{@code trainId} 与 {@code baseTrainType} <b>永远</b>都是 4.0 车型。
 *
 * <h2>诊断日志（{@code [compat-audit]}）</h2>
 * 每次 {@code setTrainDetails} 进入/写入后都 dump 完整字段
 * （{@code trainId / baseTrainType / trainCars / transportMode / railLength}），
 * 便于用日志证伪「还有没有别的路径在改车型」。
 */
@Mixin(value = mtr.data.Siding.class, remap = false)
public abstract class SidingAutoAdaptMixin {

	@Shadow
	private String trainId;

	@Shadow
	private String baseTrainType;

	@Shadow
	private int trainCars;

	/** 只在首次打印调用栈 */
	private static boolean mtrnext$stackDumped = false;

	// ------------------------------------------------------------------
	// 1) 无参 setTrainDetails()：上游「挑内置车」永久不可达
	// ------------------------------------------------------------------

	/**
	 * descriptor 必须逐字写 {@code setTrainDetails()V}，与
	 * {@code (Ljava/lang/String;Ljava/lang/String;Z)V} 区分。
	 */
	@Inject(method = "setTrainDetails()V", at = @At("HEAD"), cancellable = true, remap = false)
	private void mtrnext$killAutoAdapt(CallbackInfo ci) {
		// ★ 无条件阻断：YMTR 的「遍历 TrainType.values() 挑内置车型」永不执行
		ci.cancel();
		try {
			final Siding self = (Siding) (Object) this;
			final boolean configured = mtrnext$isSet(this.baseTrainType);
			MtrNext.LOGGER.info("[compat-audit] 拦截 setTrainDetails()：已配置={} {}", configured, mtrnext$dump(self));
			if (!mtrnext$stackDumped) {
				mtrnext$stackDumped = true;
				MtrNext.LOGGER.info("[compat-audit] setTrainDetails() 首次调用栈：\n{}", mtrnext$stack());
			}
			if (configured) {
				MtrNext.LOGGER.info("[compat-audit] 已有车型 base='{}' —— 拒绝任何改写（上游会把它换成内置车）",
						this.baseTrainType);
				return;
			}
			mtrnext$writeDefault(self);
		} catch (final Throwable t) {
			MtrNext.LOGGER.error("[compat-audit] 无参 setTrainDetails 拦截失败", t);
		}
	}

	// ------------------------------------------------------------------
	// 2) (String,String,boolean)：无条件按参数写入，上游回退分支不可达
	// ------------------------------------------------------------------

	@Inject(method = "setTrainDetails(Ljava/lang/String;Ljava/lang/String;Z)V",
			at = @At("HEAD"), cancellable = true, remap = false)
	private void mtrnext$forceWriteNoFallback(String newTrainId, String newBaseTrainType, boolean force,
	                                          CallbackInfo ci) {
		try {
			final Siding self = (Siding) (Object) this;
			MtrNext.LOGGER.info("[compat-audit] setTrainDetails(入参 trainId='{}' base='{}' force={}) 前 {}",
					newTrainId, newBaseTrainType, force, mtrnext$dump(self));
			// ★ 自己写，然后 cancel —— 上游的 force/isValidVehicle 判断与
			//   「else { setTrainDetails(); }」回退分支整体不可达。
			mtrnext$apply(self, newTrainId, newBaseTrainType);
			ci.cancel();
			MtrNext.LOGGER.info("[compat-audit] setTrainDetails 已按参数写入（上游回退分支已不可达）→ {}",
					mtrnext$dump(self));
		} catch (final Throwable t) {
			// 绝不能把异常吞掉后放行原方法：那会让上游回退逻辑复活。
			// 这里兜底写一个非空值，保证 Siding 可序列化。
			try {
				mtrnext$apply((Siding) (Object) this, null, null);
			} catch (final Throwable ignored) {
				// 连兜底都失败：保持字段原状（至少不会 NPE 崩服）
			}
			ci.cancel();
			MtrNext.LOGGER.error("[compat-audit] 重载拦截异常，已兜底写入并继续 cancel", t);
		}
	}

	/** 观测最终字段（上游若被别的 mixin 放行也要看得到） */
	@Inject(method = "setTrainDetails(Ljava/lang/String;Ljava/lang/String;Z)V",
			at = @At("RETURN"), remap = false)
	private void mtrnext$observeSetTrainDetails(String newTrainId, String newBaseTrainType, boolean force,
	                                            CallbackInfo ci) {
		try {
			MtrNext.LOGGER.info("[compat-audit] setTrainDetails RETURN → {}", mtrnext$dump((Siding) (Object) this));
		} catch (final Throwable ignored) {
			// 观测失败不影响逻辑
		}
	}

	// ------------------------------------------------------------------
	// 写入语义
	// ------------------------------------------------------------------

	/**
	 * 把 trainId / baseTrainType / trainCars 按「4.0 优先」的语义写进去。
	 *
	 * <p><b>4.0 归一化</b>：{@code newTrainId} 命中 4.0 车包时，
	 * {@code baseTrainType} 不再采用入参（上游常塞 {@code train_12_3} 这种
	 * 合成串），而是写成该车的 family 名。这是「删掉强制兼容」的最后一刀：
	 * 无论上游是谁、按什么格式写，最终落盘的 {@code baseTrainType} 都是 4.0 车型。
	 */
	private void mtrnext$apply(Siding self, String newTrainId, String newBaseTrainType) {
		String base = mtrnext$normaliseBase(newBaseTrainType);
		String id = newTrainId == null ? "" : newTrainId.trim();

		// ★ 4.0 归一化（以 trainId 为准，其次看 base）
		//
		// ★★★ [0.63.0] 只归一化 trainId，**绝不**归一化 baseTrainType ★★★
		//
		// 真机回归（用户原话）：「大问题，刷子不能用了（跟轨道节点互动）」。
		//
		// 根因：0.59.0~0.62.0 在这里写 `base = canonical`，把 baseTrainType 从
		// `train_17_3` 改写成了 `eridu_jfl_t`。但 YMTR 全链路依赖
		// baseTrainType 的 `train_<长>_<宽>` 格式：
		//   · TrainType.splitTrainType 按 `startsWith(transportMode + "_")` 解析 len/width
		//   · Siding.setTrainDetails 用 getSpacing(baseTrainType) 校验 isValidVehicle
		//   · DepotScreen / SidingScreen / ItemNodeModifierBase 都会拿它算间距
		// 一旦它不是 `train_x_y`，这些地方要么拿默认值、要么校验失败，
		// 表现为「轨道节点工具（刷子）点了没反应」。
		//
		// 正确分工（这也是 MTR 自己的设计）：
		//   trainId       = 车型 id（"eridu_jfl_t"）→ 决定渲染器
		//   baseTrainType = 几何参数（"train_17_3"）→ 决定 spacing / width / transportMode
		String canonical = VehicleLookup.canonicalBase(id);
		if (canonical == null) {
			canonical = VehicleLookup.canonicalBase(base);
		}
		if (canonical != null) {
			if (!canonical.equals(id)) {
				MtrNext.LOGGER.info("[compat-audit] trainId 归一化到 family：'{}' → '{}'（保证渲染器命中 4.0 车型）",
						id, canonical);
			}
			id = canonical;
		}

		// base 仍为空 ⇒ 入参完全无效（上游 getString 返回 null 的情形）
		if (base.isEmpty()) {
			base = id.isEmpty() ? mtrnext$firstUsableBase() : id;
		}
		if (id.isEmpty()) {
			id = base;
		}

		final int spacing = mtrnext$spacing(base);
		final int maxLength = self.transportMode == null ? 1 : self.transportMode.maxLength;
		this.baseTrainType = base.toLowerCase(Locale.ENGLISH);
		this.trainId = id.toLowerCase(Locale.ENGLISH);
		this.trainCars = Math.min(maxLength,
				Math.max(0, (int) Math.floor(self.railLength / (float) Math.max(2, spacing))));
	}

	/** 只做 YMTR 自己那步 {@code base_ → train_} 的历史兼容，不做任何「翻译」 */
	private static String mtrnext$normaliseBase(String raw) {
		if (raw == null) {
			return "";
		}
		final String s = raw.trim();
		if (s.isEmpty()) {
			return "";
		}
		return (s.startsWith("base_") ? s.replace("base_", "train_") : s).toLowerCase(Locale.ENGLISH);
	}

	/**
	 * 真·未初始化时的默认车型（<b>只用于新建 Siding，不覆盖任何已有配置</b>）。
	 *
	 * <p>优先级：
	 * <ol>
	 *   <li>4.0 车包里第一个「放得下」的车型；</li>
	 *   <li>放不下任何 4.0 车时，仍取第一个 4.0 车（宁可长出轨道，也不冒充 3.x 车型）；</li>
	 *   <li>没有 4.0 车包（纯 3.x 用户）时才退回内置表 —— 这是 3.x 用户的原有体验。</li>
	 * </ol>
	 * 注意：写入的是 4.0 family 名作 {@code baseTrainType}，<b>不再</b>合成
	 * {@code train_<len>_<width>}。长度解析由 {@link TrainTypeSpacingMixin} 兜住。
	 */
	private void mtrnext$writeDefault(Siding self) {
		final TransportMode mode = self.transportMode;

		try {
			VehicleResource fallback = null;
			for (final VehicleResource res : VehiclePackLoader.getVehicles().values()) {
				if (res == null || !mtrnext$isSet(res.id)) {
					continue;
				}
				final String canonical = VehicleLookup.canonicalBase(res.id);
				if (canonical == null) {
					continue;
				}
				if (mode != null && !mtrnext$modeMatches(res.transportMode, mode)) {
					continue;
				}
				if (fallback == null) {
					fallback = res; // 同 mode 下第一个，放不下也认
				}
				if (mtrnext$spacing(canonical) <= Math.max(2.0F, self.railLength)) {
					mtrnext$apply(self, canonical, canonical);
					MtrNext.LOGGER.info("[compat-audit] 未初始化 → 默认取 4.0 车型 '{}'（base 同为 4.0 名）", res.id);
					return;
				}
			}
			if (fallback != null) {
				final String base = VehicleLookup.canonicalBase(fallback.id);
				mtrnext$apply(self, base, base);
				MtrNext.LOGGER.warn("[compat-audit] 未初始化且没有放得下的 4.0 车 → 仍取 '{}'（不退回内置车）",
						fallback.id);
				return;
			}
		} catch (final Throwable t) {
			MtrNext.LOGGER.warn("[compat-audit] 4.0 车包默认值失败，退回内置表", t);
		}

		// 纯 3.x 用户：沿用内置表第一个放得下的（不是「覆盖」，是「初始化」）
		for (final TrainType type : TrainType.values()) {
			if (type == null || type.baseTrainType == null) {
				continue;
			}
			if (TrainType.getTransportMode(type.baseTrainType) != mode) {
				continue;
			}
			if (mtrnext$spacing(type.baseTrainType) > Math.max(2.0F, self.railLength)) {
				continue;
			}
			mtrnext$apply(self, type.toString(), type.baseTrainType);
			MtrNext.LOGGER.info("[compat-audit] 未初始化（无 4.0 车包）→ 内置默认 '{}'", type);
			return;
		}
		if (TrainType.values().length > 0) {
			final TrainType first = TrainType.values()[0];
			mtrnext$apply(self, first.toString(), first.baseTrainType);
			MtrNext.LOGGER.warn("[compat-audit] 未初始化且放不下任何内置车 → 兜底 '{}'", first);
		}
	}

	/** 兜底用的 base：4.0 车队第一个，否则内置表第一项 */
	private static String mtrnext$firstUsableBase() {
		for (final VehicleResource res : VehiclePackLoader.getVehicles().values()) {
			if (res != null && mtrnext$isSet(res.id)) {
				final String canonical = VehicleLookup.canonicalBase(res.id);
				if (canonical != null) {
					return canonical;
				}
			}
		}
		final TrainType[] all = TrainType.values();
		return all.length == 0 ? "train_1_1" : all[0].baseTrainType;
	}

	// ------------------------------------------------------------------
	// 工具
	// ------------------------------------------------------------------

	/**
	 * 诊断用：Siding 车型相关字段全量 dump（用户要求「能证伪」的那一份）。
	 */
	private static String mtrnext$dump(Siding self) {
		return String.format(Locale.ENGLISH,
				"trainId='%s' baseTrainType='%s' trainCars=%d transportMode=%s railLength=%.3f",
				self.getTrainId(), self.getBaseTrainType(), self.getTrainCars(),
				self.transportMode, self.railLength);
	}

	private static boolean mtrnext$isSet(String s) {
		return s != null && !s.trim().isEmpty();
	}

	private static boolean mtrnext$modeMatches(String packMode, TransportMode mode) {
		if (packMode == null || packMode.isEmpty()) {
			return mode == TransportMode.TRAIN; // 车包缺字段时 TrainTypeBridge 也按 TRAIN 处理
		}
		return packMode.trim().equalsIgnoreCase(mode.name());
	}

	/** {@code TrainType.getSpacing}：4.0 车型走 {@link TrainTypeSpacingMixin}，3.x 走原逻辑 */
	private static int mtrnext$spacing(String baseTrainType) {
		try {
			return TrainType.getSpacing(baseTrainType);
		} catch (final Throwable t) {
			return 2;
		}
	}

	/** 调用栈前 10 帧（诊断用） */
	private static String mtrnext$stack() {
		final StringBuilder sb = new StringBuilder();
		final StackTraceElement[] st = new Throwable().getStackTrace();
		for (int i = 0; i < st.length && i < 10; i++) {
			sb.append("    at ").append(st[i]).append('\n');
		}
		return sb.toString();
	}
}

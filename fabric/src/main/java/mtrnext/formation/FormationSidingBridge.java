package mtrnext.formation;

import mtr.client.ClientData;
import mtr.data.Siding;
import mtr.packet.IPacket;
import mtr.packet.PacketTrainDataGuiClient;
import mtrnext.MtrNext;
import mtrnext.pack.VehiclePackLoader;
import mtrnext.pack.VehicleResource;

import java.util.Locale;

/**
 * 编组 → YMTR 发车系统的桥接。
 *
 * <h2>★★★ [0.44.1] 关键修正：写错字段了 —— 渲染器解析用的是 {@code trainId} ★★★</h2>
 *
 * <p>此前（≤0.44.0）本类用反射把 4.0 车型 id 写进 Siding 的 {@code baseTrainType}，
 * 然后真机上世界里的车永远是原版模型、{@code BbModelTrainRenderer.renderCar}
 * 一次都没被调用。反编译 YMTR 后真因如下：
 *
 * <pre>
 *   // mtr/data/TrainClient.java:116-120 —— 客户端拿什么决定渲染器
 *   public TrainClient(PacketByteBuf packet) {
 *       super(packet);                                            // trainId 来自服务端
 *       TrainProperties p = TrainClientRegistry.getTrainProperties(this.trainId);
 *       this.trainRenderer = p.renderer.createTrainInstance(this); // ← 只看 trainId
 *   }
 *
 *   // mtr/data/Siding.java:523-533 —— 两个字段是**分开**的
 *   private void setTrainDetails(String trainId, String baseTrainType, boolean force) {
 *       ...
 *       this.baseTrainType = t.toLowerCase();                     // 决定间距/车宽/编组长度
 *       this.trainId      = (trainId.isEmpty() ? baseTrainType : trainId).toLowerCase(); // ← 渲染器键
 *       this.trainCars    = min(maxLength, floor(railLength / (float) spacing));
 *   }
 * </pre>
 *
 * ⇒ 只写 {@code baseTrainType} 而 {@code trainId} 仍是 {@code m_train} 之类的原版名，
 * 客户端当然还是去取原生渲染器。
 *
 * <h2>两个字段的正确分工</h2>
 * <ul>
 *   <li><b>{@code trainId}</b> = 4.0 车型 id（{@link #familyOf}），
 *       {@code TrainClientRegistry.register} 内部会 {@code toLowerCase} 存键，
 *       所以大小写不影响命中；</li>
 *   <li><b>{@code baseTrainType}</b> = <b>必须是</b> {@code <mode>_<车长>_<车宽>} 格式
 *       （如 {@code train_12_3}，由 {@link VehicleResource#length}/{@link VehicleResource#width} 动态算出）。
 *       它经 {@code TrainType.splitTrainType} 解析出
 *       {@code Train.spacing / width / transportMode}（{@code Train.java:230-233}），
 *       而且 {@code Train} 的封包构造器会用 {@code floor(railLength / spacing)}
 *       <b>重算 trainCars</b>（{@code Train.java:358}）。
 *       ⇒ 若把它写成 4.0 车型 id，{@code getSpacing} 退化成 2，
 *         整列车的间距、车宽、编组节数全部错乱。</li>
 * </ul>
 *
 * <h2>为什么不再自己反射改服务端对象</h2>
 * <p>YMTR 提供官方入口：
 * {@code Siding.setTrainIdAndBaseType(String customId, String trainType, Consumer<PacketByteBuf> sendPacket)}
 * （{@code Siding.java:273-282}）。它以
 * {@code sendPacket.accept(packet)} 把「customId + trainType」打包发出，<b>然后</b>
 * 本地也走一遍 {@code setTrainDetails}。客户端侧照 {@code SidingScreen.java:315}
 * 的用法传 {@code PacketTrainDataGuiClient.sendUpdate(IPacket.PACKET_UPDATE_SIDING, packet)}，
 * 服务端收到 {@code "train_type"} 分支（{@code Siding.java:241-245}）后执行
 * {@code setTrainDetails(...) + trains.clear()}。
 *
 * <p>⇒ 服务端权威数据由服务端自己改，我们**不再碰** {@code RailwayData.sidings}：
 * 既避免了「反射写 private 字段」的脆弱性，也保证本地与远端一致。
 */
public final class FormationSidingBridge {

	private FormationSidingBridge() {
	}

	/**
	 * 把编组应用到 Siding（配置界面点「保存并关闭」时调用，客户端线程）。
	 *
	 * @param hostSiding YMTR 的 {@link Siding} 实例（{@code HostSidingHolder} 传入）
	 * @param formation  编组
	 * @return 成功应用的车数（0 = 编组空 / 无可用车型 / 宿主对象不是 Siding）
	 */
	public static int applyToSiding(Object hostSiding, Formation formation) {
		try {
			if (formation == null || formation.cars.isEmpty()) {
				return 0;
			}
			if (!(hostSiding instanceof Siding)) {
				MtrNext.LOGGER.warn("applyToSiding: 宿主对象不是 mtr.data.Siding（{}），跳过",
						hostSiding == null ? "null" : hostSiding.getClass().getName());
				return 0;
			}
			final VehicleResource res = dominantVehicle(formation);
			if (res == null) {
				return 0;
			}
			apply((Siding) hostSiding, res, formation.cars.size());
			return formation.cars.size();
		} catch (final Throwable t) {
			MtrNext.LOGGER.error("Failed to apply formation to siding", t);
			return 0;
		}
	}

	/**
	 * 按 sidingId 把车型配置重新发给服务端（进世界后自动恢复用）。
	 *
	 * <p>客户端侧的 Siding 对象来自服务端同步（{@code ClientData.SIDINGS}），
	 * 每次进世界都会被服务端数据覆盖回去 ⇒ 必须每次重新发一次官方包，
	 * 不能只在存档里留一份本地记录。
	 *
	 * @return 是否找到客户端 Siding 并成功发送
	 */
	public static boolean syncBySidingId(long sidingId, VehicleResource res, int wantCars) {
		try {
			if (res == null) {
				return false;
			}
			final Siding siding = findClientSiding(sidingId);
			if (siding == null) {
				return false;
			}
			apply(siding, res, wantCars);
			return true;
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("syncBySidingId failed", t);
			return false;
		}
	}

	/**
	 * ★ [0.56.0] 按「编组节数」反算 {@code baseTrainType} 的车长。
	 *
	 * <p><b>问题</b>（用户：「车厢连接处消失且连接处中间隔了非常大一块」）：
	 * MTR 的 {@code trainCars} 完全由 {@code floor(railLength / spacing)} 决定
	 * （{@code Train.java} 网络包构造器重算，改 Siding 字段无效），spacing 又来自
	 * {@code baseTrainType} 的 {@code train_<len>_<width>} ⇒ 编组配 3 节、侧线只够 2 节时
	 * 只发 2 节；渲染层若再「按编组裁剪」就会留出一个空槽位。
	 *
	 * <p><b>做法</b>：把 spacing 反算成 {@code floor(railLength / wantCars)}，
	 * 让 {@code floor(railLength / spacing) == wantCars}。
	 *
	 * <p><b>两条护栏</b>（缺一不可，否则修好间隔又坏别的）：
	 * <ol>
	 *   <li>spacing <b>不小于真实车长</b>（{@code floor(res.length)}）——
	 *       车厢中心距小于车长时模型会互相插进对方车体；</li>
	 *   <li>spacing 只减不增（不超过原来的 {@code len+1}）——
	 *       编组本来就放得下时保持原生间隔，不动上游行为。</li>
	 * </ol>
	 * 两者冲突（侧线真的放不下这么多 12 格车）时保持真实 spacing，
	 * 并打一条 warn 说明「侧线太短」，交由渲染层兜底（不裁剪 ⇒ 不留空洞）。
	 */
	private static String baseTypeForCars(VehicleResource res, float railLength, int wantCars) {
		final String real = nativeBaseTypeOf(res);
		if (wantCars <= 0) {
			return real;
		}
		try {
			final int realSpacing = mtr.data.TrainType.getSpacing(real);   // len + 1
			final int realLen = Math.max(1, (int) Math.round(res.length)); // 12
			final int fitSpacing = (int) Math.floor(railLength / (float) wantCars);
			if (fitSpacing >= realSpacing || fitSpacing < realLen) {
				if (fitSpacing < realLen) {
					MtrNext.LOGGER.warn("侧线太短：{} 格放不下 {} 节 {}（每节约 {} 格），"
									+ "保持原生 spacing={}，世界里的节数由 MTR 决定",
							railLength, wantCars, res.id, realLen, realSpacing);
				}
				return real;
			}
			final int width = Math.max(1, (int) Math.round(res.width));
			final String mode = (res.transportMode == null || res.transportMode.isEmpty())
					? "train" : res.transportMode.toLowerCase(Locale.ENGLISH);
			final String fitted = mode + "_" + (fitSpacing - 1) + "_" + width;
			MtrNext.LOGGER.info("编组节数反算：wantCars={} railLength={} 真实 spacing={} → spacing={}（{}）",
					wantCars, railLength, realSpacing, fitSpacing, fitted);
			return fitted;
		} catch (final Throwable t) {
			return real;
		}
	}

	/**
	 * 真正下发：官方 API + 官方包。
	 *
	 * <p>{@code customId} 与 {@code trainType} 的分工见类注释，两者<b>不可混用</b>。
	 */
	private static void apply(Siding siding, VehicleResource res, int wantCars) {
		final String customId = familyOf(res.id);
		// ★ [0.56.0] baseTrainType 的 len 不再固定取真实车长：
		//   MTR 用 floor(railLength / spacing) 决定发几节，必须让这个商等于编组节数，
		//   否则「配 3 节只发 2 节」。护栏见 baseTypeForCars。
		final String baseTrainType = baseTypeForCars(res, siding.railLength, wantCars);
		// ★ [0.44.1] 先把渲染器「钉」到编组真正选中的那个车型变体。
		//
		//   真机日志实证：编组里选的是 ERIDU-DG1000A(M_B)，
		//   世界却渲染成 ERIDU-DG1000A(M_T)：
		//     [bbmodel] renderCar: ERIDU-DG1000A(M_T) 有 4 个 model 层
		//   原因：TrainTypeBridge 用 familyOf(res.id) 作注册键，
		//   同一家族的 (M_F)/(M_T)/(M_B) 三个变体写的是**同一个键**，
		//   最后一次注册的把前面覆盖掉了（还取决于 Map 遍历顺序）。
		//
		//   3.x 一列车只有一个 trainId，做不到「逐车不同变体」；
		//   但至少应当渲染用户真正选中的那个变体 —— 这里用编组的众数变体
		//   重新注册同一键，把 renderer 绑到 res.id（完整 id，含括号）。
		pinFamilyVariant(res);
		// ★ 官方入口：既更新本地字段，又把 "train_type" 更新包发给服务端。
		//   服务端收到后 setTrainDetails(...) + trains.clear()，
		//   下一次发车就会用 customId 建 TrainServer ⇒ 客户端 getTrainProperties(customId)
		//   命中我们注册的 4.0 车型 ⇒ BbModelTrainRenderer 被调用。
		siding.setTrainIdAndBaseType(customId, baseTrainType,
				packet -> PacketTrainDataGuiClient.sendUpdate(IPacket.PACKET_UPDATE_SIDING, packet));
		MtrNext.LOGGER.info(
				"编组已下发：siding={} trainId={} baseTrainType={}（4.0 车型 {}，长 {} 宽 {}）",
				readId(siding), customId, baseTrainType, res.id, res.length, res.width);
		// ★ [0.44.1] 链路自证：直接问 YMTR 注册表「这个 trainId 会解析成哪个渲染器」。
		//   这一行是「世界里的车会不会用我们的渲染器」的**决定性证据**——
		//   只要打印出 BbModelTrainRenderer，就说明 TrainClient 构造时
		//   getTrainProperties(trainId) 一定命中我们的 4.0 车型。
		//   （拿不到 trainId 时这里会打印 blank 的 JonModelTrainRenderer，
		//     也就顺手暴露了 setTrainDetails 因 isValidVehicle 失败而回退原版车型的情形。）
		try {
			final String resolvedId = siding.getTrainId();
			MtrNext.LOGGER.info("渲染器解析验证：trainId='{}' → renderer={}",
					resolvedId,
					mtr.client.TrainClientRegistry.getTrainProperties(resolvedId)
							.renderer.getClass().getName());
		} catch (final Throwable t) {
			MtrNext.LOGGER.warn("渲染器解析验证失败", t);
		}
	}

	/**
	 * 把家族键上的渲染器重新绑到「编组选中的那个变体」。
	 *
	 * <p>只用公开 API：{@code TrainClientRegistry.register(String, TrainProperties)}，
	 * 键相同会直接覆盖（{@code REGISTRY.put}），不会产生重复条目。
	 *
	 * <p>若该家族还没被注册过（{@code getTrainProperties} 回退成 blank，
	 * 其 renderer 是 {@code JonModelTrainRenderer} 而非我们的类），则跳过，
	 * 避免把一张 blank 配置写进注册表。
	 */
	private static void pinFamilyVariant(VehicleResource res) {
		try {
			final String key = familyOf(res.id);
			final mtr.client.TrainProperties base = mtr.client.TrainClientRegistry.getTrainProperties(key);
			if (!(base.renderer instanceof mtrnext.client.render.BbModelTrainRenderer)) {
				MtrNext.LOGGER.warn("变体固定跳过：{} 尚未注册 4.0 车型（renderer={}）",
						key, base.renderer.getClass().getName());
				return;
			}
			mtr.client.TrainClientRegistry.register(key, new mtr.client.TrainProperties(
					base.baseTrainType, base.name, base.description, base.wikipediaArticle,
					base.color, base.riderOffset, base.riderOffsetDismounting, base.bogiePosition,
					base.isJacobsBogie, base.hasGangwayConnection,
					new mtrnext.client.render.BbModelTrainRenderer(res.id), // ← 绑到具体变体
					base.sound));
		} catch (final Throwable t) {
			MtrNext.LOGGER.warn("变体固定失败（不影响主链路）", t);
		}
	}

	/**
	 * ★ [0.56.0] 读客户端 Siding 的侧线有效长度（格）；查不到返回 -1。
	 *
	 * <p>供编组界面显示「侧线 X 格 / 编组需 Y 格」——3.x 的车厢数由
	 * {@code floor(railLength / spacing)} 决定，编组比侧线长时世界里必然放不全，
	 * 让玩家在界面里就能看到原因，而不是以为「加了车却没出现」。
	 */
	public static float railLengthOf(long sidingId) {
		try {
			final Siding siding = findClientSiding(sidingId);
			return siding == null ? -1.0F : siding.railLength;
		} catch (final Throwable t) {
			return -1.0F;
		}
	}

	/**
	 * 4.0 车型 → 3.x 的 {@code baseTrainType}（{@code <mode>_<车长>_<车宽>}）。
	 *
	 * <p>反编译实证（{@code TrainType.splitTrainType}）：
	 * 形如 {@code train_12_3} 的字符串会被解析成
	 * {@code transportMode=TRAIN, length=12, width=3}，
	 * 再由 {@code getSpacing} 返回 {@code length + 1 = 13}。
	 *
	 * <p>ERIDU 车包实测 {@code length=12.0, width=3.0} ⇒ {@code train_12_3}。
	 * <b>不要写死</b>：不同车包的车长/车宽不同，这里按数值动态拼。
	 */
	static String nativeBaseTypeOf(VehicleResource res) {
		final int length = Math.max(1, (int) Math.round(res.length));
		final int width = Math.max(1, (int) Math.round(res.width));
		final String mode = (res.transportMode == null || res.transportMode.isEmpty())
				? "train"
				: res.transportMode.toLowerCase(Locale.ENGLISH);
		return mode + "_" + length + "_" + width;
	}

	/** 编组中占比最多的 4.0 车型（3.x 一列车只能有一个车型） */
	static VehicleResource dominantVehicle(Formation formation) {
		if (formation == null || formation.cars.isEmpty()) {
			return null;
		}
		String dominantId = null;
		int dominantCount = 0;
		final java.util.Map<String, Integer> counts = new java.util.HashMap<>();
		for (final Formation.Car car : formation.cars) {
			final String family = familyOf(car.vehicleId);
			final int c = counts.merge(family, 1, Integer::sum);
			if (c > dominantCount) {
				dominantCount = c;
				dominantId = car.vehicleId;
			}
		}
		return dominantId == null ? null : VehiclePackLoader.getVehicle(dominantId);
	}

	/** 车型家族名：{@code ERIDU-DG1000A(M_F)} → {@code ERIDU-DG1000A} */
	static String familyOf(String id) {
		return id == null ? "" : (id.contains("(") ? id.substring(0, id.indexOf('(')) : id);
	}

	private static Siding findClientSiding(long sidingId) {
		final java.util.Set<Siding> sidings = ClientData.SIDINGS;
		if (sidings == null) {
			return null;
		}
		for (final Siding siding : sidings) {
			if (readId(siding) == sidingId) {
				return siding;
			}
		}
		return null;
	}

	/**
	 * 读 Siding 的 {@code id}（声明在父类 {@code SerializedDataBase}，无 getter）。
	 *
	 * <p>这里只<b>读</b>，不写——写操作已经全部走官方 API。
	 */
	private static long readId(Object obj) {
		try {
			for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
				try {
					final java.lang.reflect.Field f = c.getDeclaredField("id");
					f.setAccessible(true);
					return f.getLong(obj);
				} catch (final NoSuchFieldException ignored) {
					// 继续沿继承链向上找
				}
			}
		} catch (final Throwable ignored) {
			// 读不到就当作 -1（不会命中任何编组）
		}
		return -1L;
	}
}

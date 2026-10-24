package mtrnext.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.data.TrainClient;
import mtr.render.TrainRendererBase;
import mtrnext.MtrNext;
import mtrnext.pack.VehiclePackLoader;
import mtrnext.pack.VehicleResource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * [0.35 重写] 4.0 车辆渲染器：走 MC 原生 {@code ModelPart} 树绘制。
 * <p>
 * 与旧实现的根本差别——不再手算四边面与 UV，而是让 {@link BbModelTree} 复刻上游的
 * 构造链（见该类文档），再用 {@code ModelPart.render} 绘制。于是元素旋转、组链层级、
 * box-UV 布局、inflate、mirror 一次性全部正确，不再逐项打补丁
 * （旧实现的逐项缺陷见 docs/bbox-uv-flaw.md）。
 * <p>
 * 姿态与门位语义对齐上游 {@code ModelPropertiesPart.renderNormal}（L347-364）：
 * <ul>
 *   <li>每个部件按 positionDefinitions 的正位/翻转位<b>各渲染一次</b>；翻转位
 *       X 取反且 {@code flipped=true}（绕 Y 轴 180°）</li>
 *   <li>门位移 = 门曲线值（单位像素）经 {@code setPos} 平移，MC 内部自动 /16</li>
 *   <li>门开度取 host 的 {@code Train.getDoorValue()}，与上游
 *       {@code persistentVehicleData.getDoorValue()} 同源</li>
 * </ul>
 * <p>
 * <b>[0.45.0]</b> 世界渲染的变换链改为「照抄 YMTR 原生渲染器」：
 * {@code matrices} + {@code applyAverageTransform} + {@code translate(x,y,z)}
 * + {@code rotateY(π + yaw)} + {@code rotateX(π + pitch)}，2 push / 2 pop。
 * 旧的自建 {@code PoseStack} 会丢掉矩阵里已有的相机视图旋转 ⇒ 朝向错。
 * 详见 {@link #renderCar}。
 */
public final class BbModelTrainRenderer extends TrainRendererBase {

	/** [0.39.6] 渲染异常告警限流（避免每帧刷屏） */
	private static long MTRNEXT$LAST_RENDER_WARN = 0L;
	private static void warnThrottled(String what, Throwable t) {
		final long now = System.currentTimeMillis();
		if (now - MTRNEXT$LAST_RENDER_WARN > 5000L) {
			MTRNEXT$LAST_RENDER_WARN = now;
			MtrNext.LOGGER.warn("[bbmodel] {} failed — 该车包可能有畸形数据（顶点/UV/properties）", what, t);
		}
	}

	/** [0.40.2] 诊断限流：排查「车为什么不渲染」 */
	private static long MTRNEXT$LAST_DIAG = 0L;
	/** [0.44.0] 坐标诊断限流 */
	private static long MTRNEXT$COORD_LOG = 0L;
	/** [0.50.0] 车体帧平移校正日志（只打一次） */
	private static long MTRNEXT$XF_LOG = 0L;
	/** [0.57.0] 已打印过「发车车型」的 Train 实例 id（每辆车只打一次） */
	private static final java.util.Set<Long> MTRNEXT$TRAIN_LOG = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/**
	 * [0.45.0] 渲染用的 PoseStack —— <b>直接就是框架的静态 {@code matrices}</b>。
	 *
	 * <p>0.42.0–0.44.0 曾自建 {@code new PoseStack()}（从单位矩阵起步），
	 * 想「与配置界面同构」，那是个方向性错误：
	 * <ul>
	 *   <li>{@code matrices} 在本渲染阶段<b>已经含相机视图旋转</b>
	 *       （{@code RenderTrains.render} 里先 {@code translate(-cameraPosition)}，
	 *       再 {@code TrainRendererBase.setupStaticInfo(matrices, ...)} 把它存进静态字段）；</li>
	 *   <li>自建栈从单位矩阵开始 ⇒ 丢掉视图旋转 ⇒ 模型朝向不随视角变，
	 *       真机表现为「车头/车身朝向乱、一坨」；</li>
	 *   <li>世界渲染的 x/y/z 是<b>世界坐标</b>（见 {@link #renderCar} 注释的证据链），
	 *       自建栈里再减一次相机位置 ⇒ 位置整体下沉/错位。</li>
	 * </ul>
	 * ⇒ 0.45.0 起严格照抄 YMTR 原生渲染器（反编译 {@code mtr/render/JonModelTrainRenderer.java:104-154}）
	 * 的变换链，一条都不省。
	 */
	private static com.mojang.blaze3d.vertex.PoseStack stack() {
		return matrices;
	}

	private static void diag(String msg) {
		final long now = System.currentTimeMillis();
		if (now - MTRNEXT$LAST_DIAG > 4000L) {
			MTRNEXT$LAST_DIAG = now;
			MtrNext.LOGGER.info("[bbmodel] {}", msg);
		}
	}

	private final String trainId;
	/** 本渲染器对应的列车实例（createTrainInstance 注入；门开度来源） */
	private final TrainClient train;
	/**
	 * 门 Z 位移的插值器缓存（上游 {@code PersistentVehicleData} 的
	 * {@code doorMovementInterpolations}）。
	 * <p>
	 * 上游按 {@code doorZMultiplier + "_" + doorAnimationType + "_" + flipped} 分键，
	 * 每车一份——因为插值器带状态，跨车共享会串味。
	 */
	private final Map<String, DoorMovementInterpolation> doorInterpolations = new java.util.concurrent.ConcurrentHashMap<>();
	/**
	 * 上一帧的门开度（<b>每列车一份</b>）。
	 * <p>
	 * 上游的 {@code opening} 来自 {@code getAdjustedDoorMultiplier(...) > 0}
	 * （{@code ModelPropertiesPart.java:338}）——它是**门运动方向**，
	 * 不是「门当前是否打开」。YMTR 的 {@code renderCar} 只给两个布尔
	 * （{@code doorLeftOpen} / {@code doorRightOpen}），没有方向信息，
	 * 因此这里用**门开度的变化方向**推导：比上一帧大 = 开门中。
	 * <p>
	 * 上游同名但语义不同，勿混用。
	 * <p>
	 * ★ [0.36.8] <b>这里曾经是个真 bug</b>：{@code renderCar} 是<b>每车厢</b>调一次，
	 * 而本字段是<b>每列车</b>一份 ⇒ 第 2 辆车厢读到的已是第 1 辆车厢刚写回的
	 * <b>同一个</b> {@code doorValue} ⇒ {@code doorValue >= previousDoorValue}
	 * 恒为 true ⇒ <b>{@code opening} 恒为「开门中」，关门时也算开门中</b>。
	 * 后果：① 关门时段的门时序窗口（{@code renderFromClosingDoorTime} 等）完全失效；
	 * ② 门的 Z 位移走错曲线分支（开门曲线而非关门曲线），关门动画不正确。
	 * <p>
	 * 现改为：<b>优先</b>用 YMTR 的权威 {@code TrainClient.isDoorOpening()}
	 * （<b>车辆级</b>、每帧一次算出，与上游语义一致）；
	 * 回退路径用 {@link #lastFallbackFrame} 保证<b>一帧只更新一次</b>。
	 */
	private float previousDoorValue;
	/**
	 * [0.36.8] 回退路径的帧标记：保证 {@code previousDoorValue} <b>一帧只更新一次</b>
	 * （否则多车厢列车会让 {@code opening} 恒为 true）。
	 * <p>
	 * 用 {@code System.nanoTime()} 的毫秒计数近似帧号；同帧内多次调用共用同一值。
	 */
	private long lastFallbackFrame = -1L;
	/**
	 * [0.36.8] 回退路径算出的 {@code opening}（同帧内复用）。
	 */
	private boolean fallbackOpening;

	/** 供 TrainTypeBridge 注册使用（无列车上下文，门位移退化为布尔） */
	public BbModelTrainRenderer(String trainId) {
		this(trainId, null);
	}

	private BbModelTrainRenderer(String trainId, TrainClient train) {
		this.trainId = trainId;
		this.train = train;
	}

	@Override
	public TrainRendererBase createTrainInstance(TrainClient train) {
		return new BbModelTrainRenderer(trainId, train);
	}

	@Override
	public void renderCar(int carNumber, double x, double y, double z, float rotationY, float rotationZ,
			boolean doorLeftOpen, boolean doorRightOpen) {
		try {
			// ★★★ [0.50.0] 按「编组」渲染，而不是按 MTR 算出来的车厢数 ★★★
			//
			//   用户反馈「自定义编组没有用」的根因（存档 + 日志实证）：
			//     · mtr-next-formations.nbt 里这条编组只有 1 节：ERIDU-DG1000A(M_T)
			//     · 世界里却渲染了 2 节（car=0 / car=1）
			//   因为 MTR 的 {@code Train.trainCars} 是
			//   {@code min(maxLength, floor(railLength / spacing))} 算出来的
			//   （{@code Train.java:342}，网络包构造器里<b>重算</b>，改 Siding 字段无效），
			//   编组节数根本不参与发车 —— 编组配 1 节，MTR 发 2 节，两节都用同一车型。
			//
			//   修法（纯渲染层，不动 MTR 数据）：
			//     ① 超出编组节数的车厢 <b>不渲染</b>（配几节就显示几节）；
			//     ② 每节按编组里<b>对应那一节</b>的车型渲染（头车/中间车/尾车各不相同）。
			//   后者同时解决「两个车头」：编组里第 0 节是 M_F 就只画一个车头。
			final mtrnext.formation.Formation formation = mtrnext$formation();
			// ★★★ [0.56.0] 删除「按编组节数裁剪」★★★
			//
			//   0.50.0 在这里 return 掉 carNumber >= formation.cars.size() 的车厢，
			//   想「配几节就显示几节」。但 MTR 的车厢位置是按
			//   trainCars（= floor(railLength / spacing)，见 Train.java 包构造器）
			//   一格格排开的：被跳过的那节**位置还在** ⇒ 真机表现为
			//   「车厢连接处消失、中间隔了非常大一块」。
			//
			//   正确做法分两层：
			//     ① 数据层（FormationSidingBridge.baseTypeForCars）反算 baseTrainType，
			//        让 MTR 算出的 trainCars 就等于编组节数；
			//     ② 渲染层绝不留空洞 —— 多出来的节按 trainId（众数变体）兜底渲染。
			final VehicleResource res = mtrnext$vehicleFor(carNumber, formation); // ② 逐节车型
			if (res == null) {
				diag("renderCar: getVehicle(" + trainId + ") == null");
				return;
			}
			if (res.models.isEmpty()) {
				diag("renderCar: vehicle " + trainId + " 的 models 为空");
				return;
			}
			diag("renderCar: " + trainId + " 有 " + res.models.size() + " 个 model 层");

			final boolean anyDoorOpen = doorLeftOpen || doorRightOpen;
			final float doorValue = doorValue(anyDoorOpen);
			// 上游 opening = 门运动方向（>0 开、<0 关）。YMTR 只给布尔，
			// 这里优先用 YMTR 的权威值（车辆级、每帧一次算，与上游语义一致），
			// 拿不到时才回退到自维护的门开度变化方向。
			final boolean opening = doorOpening(doorValue);

			// ★★★ [0.45.0] 变换链整体对齐 YMTR 原生渲染器（唯一正确写法）★★★
			//
			//   权威依据（CFR 反编译 libs/ymtr-fabric-1.20.1.jar，
			//   C:\tmp\ymtr_dec\mtr\render\JonModelTrainRenderer.java:104-154）：
			//   <pre>
			//     BlockPos posAverage = applyAverageTransform(this.train.getViewOffset(), x, y, z);
			//     if (posAverage == null) return;        // 超距分支：这里**没有** pushPose
			//     matrices.pushPose();                   // ① 本渲染器自己的帧
			//     matrices.translate(x, y, z);
			//     UtilitiesClient.rotateY(matrices, (float) (Math.PI + yaw));
			//     UtilitiesClient.rotateX(matrices, (float) (Math.PI + pitch));
			//     ... 画模型 ...
			//     matrices.popPose();                    // ① 的收尾
			//   </pre>
			//   而 {@code applyAverageTransform}（TrainRendererBase，反编译同文件）内部：
			//   <pre>
			//     posAverage = newBlockPos(x + cameraEntity.getX(), ...);   // 仅用于距离剔除
			//     if (shouldNotRender(...)) return null;                    // 不 push
			//     matrices.pushPose();                                      // ② 相机帧
			//     if (viewOffset != null) {
			//         matrices.translate(cam.x, cam.y, cam.z);              // ← 与 RenderTrains 的
			//         rotateYDegrees(matrices, yawDiff + (flipActive ? 180 : 0));   //   translate(-cam) 抵消
			//         matrices.translate(-viewOffset.x, -viewOffset.y, -viewOffset.z);
			//     }
			//   </pre>
			//
			//   <b>坐标语义（实证）</b>：x/y/z 是<b>世界坐标</b>，不是相机相对——
			//   {@code Train.simulateTrain} 用 {@code Rail.getPosition(...)} 取世界坐标
			//   （{@code Train.java:896}），{@code TrainClient.simulateCar} 只减去
			//   {@code vehicleRidingClient.renderPlayerAndGetOffset()}（骑乘者偏移，通常为 0）。
			//   相机偏移由 RenderTrains（{@code translate(-cameraPosition)}）与
			//   applyAverageTransform（{@code translate(+cameraPosition)}）**两边抵消**。
			//
			//   ⇒ 0.42.0 的自建 PoseStack 之所以错，不是因为多减了相机，
			//     而是因为它<b>从单位矩阵起步，丢掉矩阵里已有的相机视图旋转</b>
			//     （真机症状：车身朝向不随视角、看似「一坨」）。
			//     π 也必须保留：模型空间是 Y 向下（见 BbModelTree 的 pivot 取负），
			//     世界坐标系 Y 向上，靠 rotateX(π) 翻正；转 Y 的 π 来自 3.x 车头朝 −Z 的建模约定。
			final net.minecraft.world.phys.Vec3 viewOffset = train == null ? null : train.getViewOffset();
			final net.minecraft.core.BlockPos posAverage =
					TrainRendererBase.applyAverageTransform(viewOffset, x, y, z);
			if (posAverage == null) {
				// 超出渲染距离：applyAverageTransform 在此分支**没有** pushPose，
				// 不能 pop，否则会弹掉外层 RenderTrains 的帧（历史 bug：空栈崩端）。
				return;
			}
			// ★ [0.57.0] 发车时刻的「车型是否被换掉」铁证（每辆车只打一次）：
			//   train.trainId / baseTrainType 是**服务端发下来的 Train 包**里的值，
			//   只要这里还是 4.0 的 id（且 renderer=本类），就说明没有被换成内置车型。
			if (train != null && MTRNEXT$TRAIN_LOG.add(train.id)) {
				mtrnext.MtrNext.LOGGER.info(
						"[bbmodel][train] id={} siding={} trainId='{}' baseTrainType='{}' trainCars={} spacing={} renderer={}",
						train.id, train.sidingId, train.trainId, train.baseTrainType, train.trainCars,
						train.spacing, getClass().getSimpleName());
			}
			// 诊断日志（限流）：x/y/z 是世界坐标，cam 是相机位置，二者之差即「相对视角」量级
			final long now0 = System.currentTimeMillis();
			if (now0 - MTRNEXT$COORD_LOG > 3000L) {
				MTRNEXT$COORD_LOG = now0;
				final net.minecraft.world.phys.Vec3 cam =
						Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
				mtrnext.MtrNext.LOGGER.info(
						"[bbmodel] renderCar 坐标: x={} y={} z={} (世界坐标) cam={}/{}/{} yaw={} pitch={} car={}",
						String.format("%.2f", x), String.format("%.2f", y), String.format("%.2f", z),
						String.format("%.1f", cam.x), String.format("%.1f", cam.y), String.format("%.1f", cam.z),
						String.format("%.3f", rotationY), String.format("%.3f", rotationZ), carNumber);
			}
			// ② 是 applyAverageTransform 的帧，① 是本方法的帧：必须 2 push / 2 pop。
			try {
				// ★★★ [0.50.0] 车体帧平移校正（「扶手漂到天上 / XYZ 全部漂移」的根治）★★★
				//
				//   实测证据（0.50.0 包围盒诊断，最新日志）：
				//     车世界坐标 = (8.75, -53.00, 50.48)，相机 ≈ (11.0, -58.1, 31.8)
				//     但「顶层组元素世界包围盒」= x[19.4..22.6] y[-26.3..-20.3] z[27.0..34.8]
				//   ⇒ 模型整体被画到车位置 + (约 12, 30, -20) 的地方 —— 也就是「天上」。
				//
				//   机制（反编译 mtr/render/TrainRendererBase.applyAverageTransform 实证）：
				//     RenderTrains 先 matrices.translate(-cameraPosition)，
				//     applyAverageTransform 只在 **viewOffset != null** 时
				//     matrices.translate(+cameraOffset) 把它抵消；
				//     一旦 viewOffset 为 null（TrainClient.getViewOffset 拿不到骑乘数据），
				//     这份 -cameraPosition 就留在矩阵里 ⇒ translate(x,y,z)（世界坐标）后
				//     整体偏移 -cameraPosition ⇒ 车飘到天上。
				//
				// ★★★ [0.50.0] 车体帧观测（只记录，不干预）★★★
				//
				//   机制（反编译实证）：
				//   <ul>
				//     <li>{@code RenderTrains.render} 先 {@code translate(-cameraPosition)}；</li>
				//     <li>{@code applyAverageTransform} 只在 <b>viewOffset != null</b> 时
				//         {@code translate(+cameraOffset)} 抵消它；</li>
				//     <li>而 {@code VehicleRidingClient.getViewOffset()} 的字节码在
				//         {@code offset.isEmpty()}（没乘客）时返回 <b>aconst_null</b>，
				//         所以「没人坐这列车 → viewOffset == null」是<b>正常</b>状态；</li>
				//     <li>此时 matrices = R·T(-cam)，随后本方法 translate(x,y,z)（世界坐标）
				//         ⇒ 模型原点落到 R·(x−cam)，正好是「世界点 x 的视图坐标」——
				//         <b>这是正确的</b>，原生 JonModelTrainRenderer 走同一条链。</li>
				//   </ul>
				//   结论：这里<b>绝不能</b>把平移清零（0.50.0 前半版那样做是错的，
				//   会把车额外推 +cam）。只记录一次，便于排查。
				final org.joml.Vector3f frameT =
						matrices.last().pose().getTranslation(new org.joml.Vector3f());
				if (MTRNEXT$XF_LOG == 0L) {
					MTRNEXT$XF_LOG = 1L;
					MtrNext.LOGGER.info("[bbmodel][xf] applyAverageTransform 后平移=({},{},{}) 车世界坐标=({},{},{}) "
									+ "train={} viewOffset={}",
							String.format("%.2f", frameT.x), String.format("%.2f", frameT.y), String.format("%.2f", frameT.z),
							String.format("%.2f", x), String.format("%.2f", y), String.format("%.2f", z),
							train == null ? "null" : "非null",
							viewOffset == null ? "null" : String.format("%.2f/%0.2f/%.2f", viewOffset.x, viewOffset.y, viewOffset.z));
				}
				matrices.pushPose();
				try {
					matrices.translate(x, y, z);
					// ★ [0.55.1] 编组的「方向翻转（→ ←）」在世界渲染里生效。
					//   此前只有编组界面的预览会按 Formation.Car.forward 转 180°，
					//   世界里的 renderCar 完全没用这个标志 ⇒
					//   真机表现为「点了方向按钮没反应 / 车头朝反了」。
					final boolean forward = mtrnext$isForward(carNumber, formation);
					matrices.mulPose(com.mojang.math.Axis.YP.rotation(
							(float) (Math.PI + rotationY + (forward ? 0.0F : Math.PI))));
					matrices.mulPose(com.mojang.math.Axis.XP.rotation((float) (Math.PI + rotationZ)));
					final ResourceManager manager = Minecraft.getInstance().getResourceManager();
					for (final VehicleResource.ModelLayer layer : res.models) {
						renderLayer(manager, layer, doorValue, opening);
					}
				} finally {
					// 内层 pop 放 finally：畸形 bbmodel 抛异常也不会泄漏栈帧
					matrices.popPose();
				}
			} finally {
				// 外层 pop：收掉 applyAverageTransform 的 push
				matrices.popPose();
			}
		} catch (final Throwable t) {
			warnThrottled("renderCar", t);
		}
	}

	/**
	 * [0.50.0] 本车所属编组（按 {@code Train.sidingId} 查；无编组返回 null）。
	 *
	 * <p>{@code mtr.data.Train} 有 {@code public final long sidingId}（Train.java:92），
	 * 编组在 {@code FormationStore} 里以 {@code new UUID(0, sidingId)} 为键
	 * （与 {@code SidingScreenMixin} / {@code FormationScreen.open} 一致）。
	 */
	private mtrnext.formation.Formation mtrnext$formation() {
		if (train == null) {
			return null;
		}
		try {
			return mtrnext.formation.FormationStore.get(new java.util.UUID(0L, train.sidingId));
		} catch (final Throwable t) {
			return null;
		}
	}

	/**
	 * [0.50.0] 该节车厢应使用的车型。
	 *
	 * <p>优先取编组第 {@code carNumber} 节的 {@code vehicleId}；
	 * 编组缺失/越界/该车不在车包里时回退到 {@link #trainId}（注册时的众数变体）。
	 * 这样「编组里 M_F + M_T + M_B」会各按自己的模型渲染，不再整列同一变体。
	 */
	/**
	 * [0.55.1] 编组第 {@code carNumber} 节是否是正向。
	 *
	 * <p>没有编组 / 越界 / 任何异常时默认正向（true），保证不改变原有行为。
	 */
	private boolean mtrnext$isForward(int carNumber, mtrnext.formation.Formation formation) {
		try {
			if (formation != null && carNumber >= 0 && carNumber < formation.cars.size()) {
				return formation.cars.get(carNumber).forward;
			}
		} catch (final Throwable ignored) {
			// 任何异常都按正向处理
		}
		return true;
	}

	private VehicleResource mtrnext$vehicleFor(int carNumber, mtrnext.formation.Formation formation) {
		try {
			if (formation != null && carNumber >= 0 && carNumber < formation.cars.size()) {
				final String vehicleId = formation.cars.get(carNumber).vehicleId;
				final VehicleResource r = VehiclePackLoader.getVehicle(vehicleId);
				if (r != null) {
					return r;
				}
			}
		} catch (final Throwable ignored) {
			// 编组数据畸形时回退
		}
		return VehiclePackLoader.getVehicle(trainId);
	}

	private void renderLayer(ResourceManager manager, VehicleResource.ModelLayer layer,
			float doorValue, boolean opening) {
		if (layer.modelResource == null || layer.modelResource.isEmpty()) {
			diag("layer 无 modelResource（props-only 层）");
			return; // props-only 层：只承载 gangway/barrier 参数，无几何
		}
		// [0.36.9] 用 ModelLayer 预计算的缓存键（原先每帧每层拼一次字符串）
		final BbModelTree tree = BbModelTreeCache.tree(manager, layer.modelResource, layer.propertiesResource,
				layer.treeCacheKey);
		if (tree == null) {
			diag("bbmodel 加载失败: " + layer.modelResource);
			return;
		}
		if (tree.elements.isEmpty()) {
			diag("bbmodel 解析出 0 元素: " + layer.modelResource);
			return;
		}

		// [0.36.9] 用 ModelLayer 预解析的 ResourceLocation（原先每帧每层 new 一次）
		final VertexConsumer vc = vertexConsumers.getBuffer(
				RenderType.entityCutoutNoCull(BbModelTreeCache.texture(layer.textureLocation)));

		// [0.54.0] 删掉未使用的 GangwayProperties 加载：贯通道/端墙走
		//   {@link #gangwayProperties()}（renderConnection / renderBarrier 用），
		//   本方法只负责车体几何，此前每层每帧白做一次 properties 查找。
		// [0.38.0] 渲染计划快照：把「type=NORMAL + 位定义名非空 + 位定义组非 null」
		// 的过滤与位定义查表全部挪到加载期，渲染期只做遍历。
		// 过滤规则与下方原循环的 continue 逐条对应 ⇒ 渲染输出逐字不变。
		final LayerRenderCache plan =
				LayerRenderCache.load(manager, layer.propertiesResource, layer.positionDefinitionsResource);

		// ★ [0.48.0] 任务 2 诊断：每层一次性打印「锚点结构」。
		//
		//   目的（用数据代替猜测）：4.0 的 properties 里 part 的 names 指向 **顶层组名**，
		//   渲染时按组锚点整棵子树绘制。因此任何「部件位置不对」都只有三种可能：
		//     ① 锚点没找到（groupParts 命中 null）→ 整组不画；
		//     ② 锚点找到了但子树里节点数不对（元素丢失/被元素名映射覆盖）；
		//     ③ 锚点 pivot / cube 坐标本身算错（链变换错）。
		//   这条日志把 ①②③ 一次区分开：组名→锚点 pivot + 子树节点数 + cube 包围盒。
		mtrnext$diagAnchors(layer.treeCacheKey, tree, plan);

		// 上游 ModelPropertiesPart.java:317 —— 闪烁是 **per-part** 的：
		// 每条 wrapper 在 toModelPropertiesPartAndPositionDefinition 里
		// （ModelPropertiesPartWrapper.java:80-105）把自己的 flashOnTime/flashOffTime
		// 传给新建的 ModelPropertiesPart 实例，renderNormal 读的是本实例字段。
		// ⇒ 每个部件用自己的 flash 配置判定，不能整层共享。
		final long now = System.currentTimeMillis();

		// 上游 renderNormal 的粒度是「部件记录 × 位置实例」：
		// properties.parts[] 每条记录按自己的 names 取锚点节点，再对每个位置
		// 渲染一次。因此同一组可被多条记录引用（ERIDU 的 men2 就有两条：
		// 一条 +11 只走正位、一条 −11 只走翻转位），按组名折叠会丢掉一条。
		for (final LayerRenderCache.PlanPart planPart : plan.parts) {
			final Properties4xLoader.PartInfo info = planPart.info;
			// 上游 ModelPropertiesPart.java:339-345 —— 门行程窗口外不渲染该部件。
			// 上游用 x/z = Integer.MAX_VALUE 把部件推离视野，而不是跳过绘制。
			final boolean shouldRender = info.shouldRenderAt(doorValue, opening);
			// 上游 ModelPropertiesPart.java:317 —— per-part 闪烁判定
			final boolean flashOn = info.flashOn(now);
			for (final String name : info.names) {
				final ModelPart anchor = tree.groupParts.get(name);
				if (anchor == null) {
					continue;
				}
				for (final PositionDefinitions4xLoader.PositionGroup group : planPart.groups) {
					for (final float[] p : group.positions) {
						drawInstance(anchor, vc, info, false, p[0], p[1], p[2], doorValue, opening, shouldRender, flashOn);
					}
					// 上游 PositionDefinitions: accept(-x, y, z, true) —— X 取反 + flipped
					for (final float[] p : group.positionsFlipped) {
						drawInstance(anchor, vc, info, true, -p[0], p[1], p[2], doorValue, opening, shouldRender, flashOn);
					}
				}
			}
		}
	}

	/**
	 * [0.48.0] 任务 2 诊断（每层只打印一次）：把「组名 → 锚点」的结构摊开。
	 *
	 * <p>4.0 的 {@code properties} 里 part 的 {@code names} 指向 <b>bbmodel 顶层组名</b>，
	 * 渲染按组锚点整棵子树绘制。所以「某系列部件位置不对」必然落在三种情况之一：
	 * <ol>
	 *   <li>锚点没命中（{@code groupParts} 返回 null）→ 整组不画；</li>
	 *   <li>锚点命中但子树节点数异常（元素丢失，或被「元素名 → 元素节点」映射覆盖）；</li>
	 *   <li>锚点 pivot / cube 坐标本身算错（组链变换）。</li>
	 * </ol>
	 * 这条日志把三者一次区分开：打印 pivot、子树节点数、cube 数、cube 包围盒（像素）。
	 */
	private static final java.util.Set<String> MTRNEXT$DIAG_LAYERS =
			java.util.concurrent.ConcurrentHashMap.newKeySet();

	private static void mtrnext$diagAnchors(String layerKey, BbModelTree tree, LayerRenderCache plan) {
		if (layerKey == null || !MTRNEXT$DIAG_LAYERS.add(layerKey)) {
			return;
		}
		try {
			final StringBuilder sb = new StringBuilder();
			sb.append("[bbmodel][diag] 层 ").append(layerKey)
					.append("：顶层组=").append(tree.groupNames.size())
					.append(" 元素=").append(tree.elements.size())
					.append(" NORMAL部件=").append(plan.parts.size()).append('\n');
			sb.append("   groupParts 键总数=").append(tree.groupParts.size())
					.append("（含元素名映射）\n");

			// [0.62.0] 可见性硬指标：properties 里每个 part 的每个 name 是否都能取到锚点。
			//   BB5.0 格式（groups/outliner 分离）曾让组名全变空串 ⇒ 这里恒为 0/N，
			//   表现为「整车一个部件都不画」。
			int hitNames = 0;
			int totalNames = 0;
			for (final LayerRenderCache.PlanPart planPart : plan.parts) {
				for (final String n : planPart.info.names) {
					totalNames++;
					if (tree.groupParts.get(n) != null) {
						hitNames++;
					}
				}
			}
			sb.append("   部件名命中 anchor=").append(hitNames).append("/").append(totalNames).append('\n');
			// ★ [0.50.0] 遍历<b>全部顶层组</b>（不再只看 properties 引用的组）：
			//   M_T 的 properties 不含 hand/hand_n，但 bbmodel 里有 —— 只有这样
			//   才能看到「扶手」到底被建到哪去了。
			final java.util.Set<String> names = new java.util.LinkedHashSet<>(tree.groupNames);
			for (final String name : names) {
				final ModelPart anchor = tree.groupParts.get(name);
				if (anchor == null) {
					sb.append("   ").append(name).append(" -> ★锚点缺失\n");
					continue;
				}
				sb.append(String.format("   %-16s 局部包围盒=%s%n", name, mtrnext$groupBounds(tree, name)));
			}
			MtrNext.LOGGER.info(sb.toString());
		} catch (final Throwable t) {
			MtrNext.LOGGER.warn("[bbmodel][diag] 锚点诊断失败", t);
		}
	}

	/**
	 * [0.50.0] 某顶层组全部元素在世界坐标下的包围盒（诊断用）。
	 *
	 * <p>链 = {@code groupPaths[组名]}（root→锚点）+ {@code Element.chain}（锚点→元素节）
	 * + 元素 cube 中心（{@code Element.centerX/Y/Z}）。三者都是构建期存下的纯数据，
	 * 渲染期只做矩阵乘，不改任何状态。
	 *
	 * @return 形如 {@code n=136 x[..] y[..] z[..]}；无元素时返回 {@code n=0}
	 */
	private static String mtrnext$groupBounds(BbModelTree tree, String groupName) {
		try {
			final java.util.List<ModelPart> rootToAnchor = tree.groupPaths.get(groupName);
			if (rootToAnchor == null) {
				return "链缺失";
			}
			float lox = Float.MAX_VALUE, loy = Float.MAX_VALUE, loz = Float.MAX_VALUE;
			float hix = -Float.MAX_VALUE, hiy = -Float.MAX_VALUE, hiz = -Float.MAX_VALUE;
			int count = 0;
			for (final BbModelTree.Element e : tree.elements) {
				if (!groupName.equals(e.groupName)) {
					continue;
				}
				// ★ 从**单位矩阵**起算 ⇒ 得到「模型局部坐标」（MC 坐标、单位：格）。
				//   这样结果与相机朝向、车体世界位置全部解耦，可与 bbmodel 的
				//   from/to/origin 直接对照：
				//     世界坐标 ≈ (车x + 局部x, 车y − 局部y, 车z − 局部z)   （rotateX(π)）
				final com.mojang.blaze3d.vertex.PoseStack ps = new com.mojang.blaze3d.vertex.PoseStack();
				for (final ModelPart p : rootToAnchor) {
					p.translateAndRotate(ps);
				}
				for (final ModelPart p : e.chain) {
					p.translateAndRotate(ps);
				}
				ps.translate(e.centerX, e.centerY, e.centerZ);
				final org.joml.Vector3f w = ps.last().pose().getTranslation(new org.joml.Vector3f());
				lox = Math.min(lox, w.x); loy = Math.min(loy, w.y); loz = Math.min(loz, w.z);
				hix = Math.max(hix, w.x); hiy = Math.max(hiy, w.y); hiz = Math.max(hiz, w.z);
				count++;
			}
			if (count == 0) {
				return "n=0（该组无元素）";
			}
			return String.format("n=%d x[%.1f..%.1f] y[%.1f..%.1f] z[%.1f..%.1f]",
					count, lox, hix, loy, hiy, loz, hiz);
		} catch (final Throwable t) {
			return "计算失败:" + t.getClass().getSimpleName();
		}
	}

	/**
	 * 绘制一次实例，对齐上游
	 * {@code modelPart.render(graphicsHolder, x, y, z, flipped ? π : 0, light, overlay)}
	 * （{@code ModelPropertiesPart.java:364}）。
	 * <p>
	 * 渲染对象是<b>组锚点节点</b>（上游 {@code nameToPart} 的 ModelPartExtension），
	 * 故门位移移动整个顶层组，与上游一致。
	 * <p>
	 * <b>「推离视野」语义</b>：上游在不该渲染时把 x/z（门时序窗口外）
	 * 或 y（闪烁熄灭）设成 {@code Integer.MAX_VALUE}，而不是跳过绘制——
	 * 这样渲染批次与光照计算保持一致。本实现用同样的手法。
	 *
	 * @param flipped      上游 positionsFlipped 槽：绕 Y 轴 180°
	 * @param px           位定义 X 偏移（像素，翻转位已取反）
	 * @param shouldRender 门时序窗口内才渲染（上游 L339-345）
	 * @param flashOn      闪烁点亮中（上游 L317/L320）
	 */
	private void drawInstance(ModelPart anchor, VertexConsumer vc, Properties4xLoader.PartInfo info,
			boolean flipped, float px, float py, float pz, float doorValue, boolean opening,
			boolean shouldRender, boolean flashOn) {
		// [0.36.9] 门位移改写入**可复用缓冲区**（原先每次调用 new 一个 float[2]）
		doorOffsetPixels(info, flipped, doorValue, opening);

		// ★★★ [0.57.0] 逐字照抄 4.0：改 ModelPart 的字段，让 MC 自己去 translate + rotate ★★★
		//
		//   上游三处原文（docs/_upstream/ModelPropertiesPart.java + CFR 反编译 mtr405.jar）：
		//   ① 位置（单位：像素，不除 16）——
		//      x = shouldRender ? (float)(partDetails.x + getDoorAnimationX(...)) : Integer.MAX_VALUE;
		//      z = shouldRender ? (float)(partDetails.z + getInterpolatedDoorValue(...)) : Integer.MAX_VALUE;
		//      y = flashOn ? (float) partDetails.y : Integer.MAX_VALUE;      // L320
		//   ② 优化渲染路径 L351-361 证明量纲：
		//      graphicsHolder.push(); translate(x / 16, y / 16, z / 16);
		//      if (partDetails.flipped) rotateYDegrees(180);  ... pop();
		//   ③ 普通路径 L364：
		//      modelPart.render(graphicsHolder, x, y, z, partDetails.flipped ? π : 0, light, overlay);
		//   ④ ModelPartExtension.render L101-108 里只做两件事：
		//      modelPart.setPos(x, y, z);  modelPart.yRot = rotateY;  modelPart.render(...)
		//   MC 的 translateAndRotate 再 translate(x/16,y/16,z/16)、并按 Z→Y→X 顺序乘旋转。
		//
		//   ⇒ 4.0 **从不自己叠矩阵**，所以这里既不 push/translate/pop，也不做 pivot 换算。
		//   0.56.0 的 T(pivot)·R_y(π)·T(-pivot) 是错的（真机「错位更多」），已删除；
		//   绕「模型原点转」的旧写法同样不是上游语义，一并由本实现取代。
		//
		//   注意：anchor 是 PartPose.ZERO 的空节点，其子树（组链 + 元素）自带各自
		//   PartPose 的平移/旋转；把 x/y/z/yRot 写在这里，等价于在组的外层做
		//   translate + rotate，与上游 "对组 ModelPartExtension 调用 render" 同构。
		final float x = shouldRender ? (px + doorOffsetX) : (float) Integer.MAX_VALUE;
		final float z = shouldRender ? (pz + doorOffsetZ) : (float) Integer.MAX_VALUE;
		final float y = flashOn ? py : (float) Integer.MAX_VALUE;

		anchor.x = x;
		anchor.y = y;
		anchor.z = z;
		anchor.yRot = flipped ? (float) Math.PI : 0.0F;
		// x/y/z 每次 drawInstance 都重设 ⇒ 共享节点无残留（4.0 也是这么复用同一批 ModelPart）
		anchor.render(matrices, vc, 0xF000F0, OverlayTexture.NO_OVERLAY);
	}

	/**
	 * 门位移（像素）：上游
	 * {@code partDetails.x + doorAnimationType.getDoorAnimationX(doorXMultiplier, partDetails.flipped, Math.max(doorValue, doorOverrideValue))}
	 * 与 {@code partDetails.z + getInterpolatedDoorValue(doorAnimationType, doorZMultiplier, partDetails.flipped, doorOverrideValue, opening)}。
	 * <p>
	 * {@code flipped} 传入曲线后由内部的 {@code copySign} 自动反号，
	 * 因此镜像侧无需另算符号。
	 * <p>
	 * <b>time 参数就是 doorValue 本身</b>（上游 L347 传的是门开度，不是布尔）：
	 * 门开度随时间从 1 递减到 0，关门时曲线自然回退——所以
	 * {@code STANDARD_SLOW} 的关门分段曲线（{@code DoorAnimationType.java:142-151}）
	 * 才能被触发。此前用 {@code doorsOpen ? doorValue : 0} 会让关门曲线永远取不到。
	 * <p>
	 * <b>Z 位移额外过一层 500 ms 线性插值</b>（上游
	 * {@code PersistentVehicleData.getInterpolatedDoorValue} → {@code DoorMovementInterpolation}），
	 * 用于消除开门↔关门方向翻转处的跳变；<b>X 位移不插值</b>（上游 L347 走
	 * {@code getDoorAnimationX}，无插值层）。
	 *
	 * @param opening 上游语义：门<b>运动方向</b>（true = 开门中，false = 关门中）
	 */
	private void doorOffsetPixels(Properties4xLoader.PartInfo info, boolean flipped,
			float doorValue, boolean opening) {
		if (info == null || (info.doorXMultiplier == 0.0F && info.doorZMultiplier == 0.0F)) {
			doorOffsetX = 0.0F;
			doorOffsetZ = 0.0F;
			return;
		}
		// [0.36.7] 用加载期预解析的枚举（原先每帧每部件重新 parse 字符串）
		final DoorAnimation4x anim = info.doorAnimation;
		final float time = Math.abs(doorValue);
		final float dx = anim.getDoorAnimationX(info.doorXMultiplier, flipped, time);
		// 上游：曲线值 → 插值层（按 key 复用插值器）→ 与 doorOverride 取绝对值较大者
		final float rawZ = anim.getDoorAnimationZ(info.doorZMultiplier, flipped, time, opening);
		final String key = info.interpolationKeyPrefix + flipped;
		final DoorMovementInterpolation interpolation =
				doorInterpolations.computeIfAbsent(key, k -> new DoorMovementInterpolation());
		final float dz = interpolation.setAndGet(rawZ, opening);
		doorOffsetX = dx;
		doorOffsetZ = dz;
	}

	/**
	 * [0.36.9] 门位移的 X / Z 分量（像素）——<b>可复用输出缓冲</b>。
	 * <p>
	 * 原先 {@code doorOffsetPixels} 每次调用 {@code new float[2]} 返回结果，
	 * 每帧每渲染实例一次 ⇒ 每秒数千次短命数组分配（GC 压力）。
	 * 改为主动写出两个分量，调用方（{@code drawInstance}）立即读取，
	 * 期间不跨调用 ⇒ 无并发/重入问题（{@code drawInstance} 不递归）。
	 */
	private float doorOffsetX;
	private float doorOffsetZ;

	/**
	 * [0.36.8] 门运动方向（上游 {@code opening} 语义）。
	 * <p>
	 * <b>优先</b>用 YMTR 的 {@code TrainClient.isDoorOpening()}：
	 * <ul>
	 *   <li>它是 <b>TrainClient（车辆）级</b>的 {@code doorOpening} 字段，</li>
	 *   <li>由 {@code doorValue > oldDoorValue} 算出，<b>每帧一次</b>，</li>
	 *   <li>与上游 {@code PersistentVehicleData.getAdjustedDoorMultiplier(...) > 0}
	 *       的<b>车辆级</b>语义一致。</li>
	 * </ul>
	 * YMTR 字节码实证（{@code mtr/data/TrainClient.class}）：
	 * <pre>
	 *   getfield  doorValue:F
	 *   getfield  oldDoorValue:F
	 *   fcmpl / ifle          ← doorValue &gt; oldDoorValue ? 开门中 : 关门中
	 *   putfield doorOpening:Z
	 *   ...
	 *   renderCar:(IDDDFFZZ)V ← 紧接着调用，顺序正确
	 * </pre>
	 * <p>
	 * <b>回退</b>（{@code train == null}，仅注册模板时）：用自维护的门开度变化方向，
	 * 但<b>一帧只更新一次</b>（{@link #lastFallbackFrame}）——
	 * 否则多车厢列车会让第 2 辆车厢读到本帧刚写回的值 ⇒ {@code opening} 恒为 true。
	 *
	 * @param doorValue 当前门开度（绝对值）
	 */
	private boolean doorOpening(float doorValue) {
		if (train != null) {
			try {
				return train.isDoorOpening();
			} catch (final Throwable ignored) {
				// 老版本宿主没有这个方法 → 走回退
			}
		}
		// 回退：一帧只更新一次（同帧多车厢共用同一 opening）
		final long frame = System.nanoTime() / 1_000_000L;
		if (frame != lastFallbackFrame) {
			lastFallbackFrame = frame;
			fallbackOpening = fallbackOpening(doorValue, previousDoorValue);
			previousDoorValue = doorValue;
		}
		return fallbackOpening;
	}

	/**
	 * [0.36.8] 回退路径的「门运动方向」纯函数（可离线回归测试）。
	 * <p>
	 * 语义 = <b>门开度不小于上一帧 ⇒ 开门中</b>。
	 * <p>
	 * ★ 这里曾经有个<b>真 bug</b>：原实现把这条判定直接写在
	 * {@code renderCar} 里，而 {@code renderCar} 是<b>每车厢</b>调一次、
	 * 状态 {@code previousDoorValue} 却只有<b>每列车</b>一份
	 * ⇒ 第 2 辆车厢读到的已是第 1 辆车厢刚写回的<b>同一个</b>门开度
	 * ⇒ 判定恒为 true ⇒ <b>关门时也被当成开门中</b>。
	 * <p>
	 * 修法见 {@link #doorOpening(float)}：优先用车辆级的
	 * {@code TrainClient.isDoorOpening()}，回退路径用帧标记保证一帧只更新一次。
	 * 本方法只负责<b>单次比较</b>的语义，供测试直接验证。
	 *
	 * @param doorValue     当前门开度（绝对值）
	 * @param previousValue 上一帧门开度
	 */
	public static boolean fallbackOpening(float doorValue, float previousValue) {
		return doorValue >= previousValue;
	}

	/** host 门开度；拿不到时按布尔退化 */
	private float doorValue(boolean anyDoorOpen) {
		try {
			if (train != null) {
				return Math.abs(train.getDoorValue());
			}
		} catch (final Throwable ignored) {
			// 退化到布尔
		}
		return anyDoorOpen ? 1.0F : 0.0F;
	}

	@Override
	public void renderConnection(Vec3 fromX, Vec3 fromZ, Vec3 toX, Vec3 toZ, Vec3 fromXFlip, Vec3 fromZFlip,
			Vec3 toXFlip, Vec3 toZFlip, double y, double width, double height, float angleX, float angleZ) {
		try {
			final Properties4xLoader.GangwayProperties props = gangwayProperties();
			if (props == null) {
				return;
			}
			drawConnectionFace(fromX, fromZ, toX, toZ, fromXFlip, fromZFlip, toXFlip, toZFlip,
					y, props.gangwayInnerSide, props.gangwayHeight);
			drawConnectionCap(fromX, fromZ, toX, toZ, y, props.gangwayInnerTop);
			drawConnectionCap(fromXFlip, fromZFlip, toXFlip, toZFlip, y, props.gangwayInnerTop);
			drawConnectionCap(fromX, toX, fromZ, toZ, y - props.gangwayHeight, props.gangwayInnerBottom);
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("renderConnection failed", t);
		}
	}

	@Override
	public void renderBarrier(Vec3 fromX, Vec3 fromZ, Vec3 toX, Vec3 toZ, Vec3 fromXFlip, Vec3 fromZFlip,
			Vec3 toXFlip, Vec3 toZFlip, double y, double width, double height, float angleX, float angleZ) {
		try {
			final Properties4xLoader.GangwayProperties props = gangwayProperties();
			if (props == null) {
				return;
			}
			drawConnectionFace(fromX, fromZ, toX, toZ, fromXFlip, fromZFlip, toXFlip, toZFlip,
					y, props.barrierInnerSide, props.barrierHeight);
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("renderBarrier failed", t);
		}
	}

	/** 贯通道/端墙属性按"每车"语义取第 0 层（不是遗漏，见 properties_*.json 结构） */
	private Properties4xLoader.GangwayProperties gangwayProperties() {
		final VehicleResource res = VehiclePackLoader.getVehicle(trainId);
		if (res == null || res.models.isEmpty()) {
			return null;
		}
		final VehicleResource.ModelLayer layer = res.models.get(0);
		if (layer.propertiesResource == null || layer.propertiesResource.isEmpty()) {
			return null;
		}
		return Properties4xLoader.load(Minecraft.getInstance().getResourceManager(), layer.propertiesResource);
	}

	private void drawConnectionFace(Vec3 fromX, Vec3 fromZ, Vec3 toX, Vec3 toZ,
			Vec3 fromXFlip, Vec3 fromZFlip, Vec3 toXFlip, Vec3 toZFlip,
			double y, String sideTex, float faceHeight) {
		if (sideTex == null || sideTex.isEmpty()) {
			return;
		}
		final VertexConsumer vc = vertexConsumers.getBuffer(
				RenderType.entityCutoutNoCull(texLocation(sideTex)));
		final var pose = stack().last().pose();
		connectionQuad(vc, pose, fromX, toZ, y, faceHeight);
		connectionQuad(vc, pose, fromXFlip, toZFlip, y, faceHeight);
	}

	private void drawConnectionCap(Vec3 a, Vec3 b, Vec3 c, Vec3 d, double capY, String capTex) {
		if (capTex == null || capTex.isEmpty()) {
			return;
		}
		final VertexConsumer vc = vertexConsumers.getBuffer(
				RenderType.entityCutoutNoCull(texLocation(capTex)));
		final var pose = stack().last().pose();
		vc.vertex(pose, (float) a.x, (float) capY, (float) a.z).color(255, 255, 255, 255).uv(0, 0).endVertex();
		vc.vertex(pose, (float) b.x, (float) capY, (float) b.z).color(255, 255, 255, 255).uv(1, 0).endVertex();
		vc.vertex(pose, (float) c.x, (float) capY, (float) c.z).color(255, 255, 255, 255).uv(1, 1).endVertex();
		vc.vertex(pose, (float) d.x, (float) capY, (float) d.z).color(255, 255, 255, 255).uv(0, 1).endVertex();
	}

	private void connectionQuad(VertexConsumer vc, org.joml.Matrix4f pose,
			Vec3 left, Vec3 right, double topY, double h) {
		final float lx = (float) left.x, lz = (float) left.z;
		final float rx = (float) right.x, rz = (float) right.z;
		final float by = (float) (topY - h);
		vc.vertex(pose, lx, by, lz).color(255, 255, 255, 255).uv(0, 1).endVertex();
		vc.vertex(pose, rx, by, rz).color(255, 255, 255, 255).uv(1, 1).endVertex();
		vc.vertex(pose, rx, (float) topY, rz).color(255, 255, 255, 255).uv(1, 0).endVertex();
		vc.vertex(pose, lx, (float) topY, lz).color(255, 255, 255, 255).uv(0, 0).endVertex();
	}

	/** 车包贴图字符串 → ResourceLocation（供本类与 FormationPreviewRenderer 共用） */
	static ResourceLocation texLocation(String resource) {
		return BbModelTreeCache.texture(resource);
	}
}

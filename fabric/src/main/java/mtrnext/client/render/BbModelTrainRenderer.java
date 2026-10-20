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
 */
public final class BbModelTrainRenderer extends TrainRendererBase {

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
	 * 上一帧的门开度（每车一份）。
	 * <p>
	 * 上游的 {@code opening} 来自 {@code getAdjustedDoorMultiplier(...) > 0}
	 * （{@code ModelPropertiesPart.java:338}）——它是**门运动方向**，
	 * 不是「门当前是否打开」。YMTR 的 {@code renderCar} 只给两个布尔
	 * （{@code doorLeftOpen} / {@code doorRightOpen}），没有方向信息，
	 * 因此这里用**门开度的变化方向**推导：比上一帧大 = 开门中。
	 * <p>
	 * 上游同名但语义不同，勿混用。
	 */
	private float previousDoorValue;

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
			final VehicleResource res = VehiclePackLoader.getVehicle(trainId);
			if (res == null || res.models.isEmpty()) {
				return;
			}

			final boolean anyDoorOpen = doorLeftOpen || doorRightOpen;
			final float doorValue = doorValue(anyDoorOpen);
			// 上游 opening = 门运动方向（>0 开、<0 关）。YMTR 只给布尔，
			// 这里用门开度的变化方向推导（比上一帧大 = 开门中）。
			final boolean opening = doorValue >= previousDoorValue;
			previousDoorValue = doorValue;

			matrices.pushPose();
			matrices.translate(x, y, z);
			// 上游 host 契约：先 Y(π + yaw)，再 X(π + pitch)。
			// renderCar 的 rotationZ 实为 pitch（YMTR 用它判断升降坡，见
			// JonModelTrainRenderer.renderCar 的 hasPitchAscending/Descending），不是滚转。
			matrices.mulPose(com.mojang.math.Axis.YP.rotation((float) (Math.PI + rotationY)));
			matrices.mulPose(com.mojang.math.Axis.XP.rotation((float) (Math.PI + rotationZ)));

			final ResourceManager manager = Minecraft.getInstance().getResourceManager();
			for (final VehicleResource.ModelLayer layer : res.models) {
				renderLayer(manager, layer, doorValue, opening);
			}

			// host 在 TrainRendererBase.applyAverageTransform 里 push 一次，
			// renderCar 内再 push 一次，收尾必须 pop 两次
			// （对齐 YMTR JonModelTrainRenderer 的 876/882 两条 popPose）。
			matrices.popPose();
			matrices.popPose();
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("bbmodel renderCar failed", t);
		}
	}

	private void renderLayer(ResourceManager manager, VehicleResource.ModelLayer layer,
			float doorValue, boolean opening) {
		if (layer.modelResource == null || layer.modelResource.isEmpty()) {
			return; // props-only 层：只承载 gangway/barrier 参数，无几何
		}
		final BbModelTree tree = BbModelTreeCache.tree(manager, layer.modelResource, layer.propertiesResource);
		if (tree == null || tree.elements.isEmpty()) {
			return;
		}

		final VertexConsumer vc = vertexConsumers.getBuffer(
				RenderType.entityCutoutNoCull(BbModelTreeCache.texture(layer.textureResource)));

		final Properties4xLoader.GangwayProperties props =
				Properties4xLoader.load(manager, layer.propertiesResource);
		// positionDefinitions：部件名（= 顶层组名）→ 正位 / 翻转位
		final Map<String, PositionDefinitions4xLoader.PositionGroup> positions =
				PositionDefinitions4xLoader.load(manager, layer.positionDefinitionsResource);

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
		for (final Properties4xLoader.PartInfo info : props.parts) {
			if (!"NORMAL".equals(info.type)) {
				continue; // DISPLAY/FLOOR/DOORWAY 不参与常规几何渲染
			}
			if (info.positionDefinitionNames.isEmpty()) {
				continue; // 上游 getPositionDefinition 未被调用 → 该记录不产出几何
			}
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
				for (final String defName : info.positionDefinitionNames) {
					final PositionDefinitions4xLoader.PositionGroup group = positions.get(defName);
					if (group == null) {
						continue;
					}
					for (final float[] p : group.positions) {
						drawInstance(anchor, vc, info, false, p[0], p[1], p[2], doorValue, opening, shouldRender, flashOn);
					}
					for (final float[] p : group.positionsFlipped) {
						drawInstance(anchor, vc, info, true, -p[0], p[1], p[2], doorValue, opening, shouldRender, flashOn);
					}
				}
			}
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
		final float[] door = doorOffsetPixels(info, flipped, doorValue, opening);
		matrices.pushPose();
		// 位移统一按像素给出，这里一次性转格
		float ox = (px + door[0]) / 16.0F;
		float oy = py / 16.0F;
		float oz = (pz + door[1]) / 16.0F;
		if (!shouldRender) {
			ox = Integer.MAX_VALUE;
			oz = Integer.MAX_VALUE;
		}
		if (!flashOn) {
			oy = Integer.MAX_VALUE;
		}
		matrices.translate(ox, oy, oz);
		if (flipped) {
			matrices.mulPose(com.mojang.math.Axis.YP.rotation((float) Math.PI));
		}
		anchor.render(matrices, vc, 0xF000F0, OverlayTexture.NO_OVERLAY);
		matrices.popPose();
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
	private float[] doorOffsetPixels(Properties4xLoader.PartInfo info, boolean flipped,
			float doorValue, boolean opening) {
		if (info == null || (info.doorXMultiplier == 0.0F && info.doorZMultiplier == 0.0F)) {
			return ZERO;
		}
		final DoorAnimation4x anim = DoorAnimation4x.parse(info.doorAnimationType);
		final float time = Math.abs(doorValue);
		final float dx = anim.getDoorAnimationX(info.doorXMultiplier, flipped, time);
		// 上游：曲线值 → 插值层（按 key 复用插值器）→ 与 doorOverride 取绝对值较大者
		final float rawZ = anim.getDoorAnimationZ(info.doorZMultiplier, flipped, time, opening);
		final String key = DoorMovementInterpolation.key(info.doorZMultiplier, info.doorAnimationType, flipped);
		final DoorMovementInterpolation interpolation =
				doorInterpolations.computeIfAbsent(key, k -> new DoorMovementInterpolation());
		final float dz = interpolation.setAndGet(rawZ, opening);
		return new float[]{dx, dz};
	}

	private static final float[] ZERO = {0.0F, 0.0F};

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
		final var pose = matrices.last().pose();
		connectionQuad(vc, pose, fromX, toZ, y, faceHeight);
		connectionQuad(vc, pose, fromXFlip, toZFlip, y, faceHeight);
	}

	private void drawConnectionCap(Vec3 a, Vec3 b, Vec3 c, Vec3 d, double capY, String capTex) {
		if (capTex == null || capTex.isEmpty()) {
			return;
		}
		final VertexConsumer vc = vertexConsumers.getBuffer(
				RenderType.entityCutoutNoCull(texLocation(capTex)));
		final var pose = matrices.last().pose();
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

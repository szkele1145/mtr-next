package mtrnext.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtrnext.MtrNext;
import mtrnext.formation.Formation;
import mtrnext.formation.FormationStore;
import mtrnext.pack.VehiclePackLoader;
import mtrnext.pack.VehicleResource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;

import java.util.Map;
import java.util.UUID;

/**
 * [0.35 重写] 编组屏 3D 预览——与主渲染器共用 MC 原生 {@code ModelPart} 树。
 * <p>
 * 旧实现走手算四边面路径（{@code BbBox} 直绘），与主渲染器同样存在
 * 旋转丢失 / box-UV 错位等缺陷。改为共用 {@link BbModelTree} 后，
 * 预览与实车渲染的几何必然一致（同一份构造链）。
 * <p>
 * 预览是静态语境，门按"完全打开"取值（time=1），与主渲染器的门曲线同源。
 */
public final class FormationPreviewRenderer {

	private FormationPreviewRenderer() {
	}

	public static void render(PoseStack poseStack, MultiBufferSource buffers, UUID sidingKey) {
		try {
			final Formation formation = FormationStore.get(sidingKey);
			if (formation == null || formation.cars.isEmpty()) {
				return;
			}

			// ★★★ [0.50.0] 预览排布：按每节车的**实际车长**依次排开 ★★★
			//
			//   0.49 及以前写死 {@code translate((i - (n-1)/2) * 1.5, 0, 0)} ——
			//   间距 1.5 格，而 ERIDU 单节车长 12 格 ⇒ 编组里所有车**完全叠在一起**
			//   （真机表现：编组屏里只看得到一节车，多个车的门/扶手/端墙互相穿插，
			//    看起来像「部件漂到天上 / XYZ 全乱」）。
			//   这正是用户说的「自定义编组没有用」在预览里的直接观感。
			//
			//   现改为：总长 = Σ(车长 + 0.5 车钩间隙)，逐节累加游标居中摆放。
			final double gap = 0.5;
			double totalLength = 0.0;
			for (final Formation.Car car : formation.cars) {
				final VehicleResource res = VehiclePackLoader.getVehicle(car.vehicleId);
				totalLength += (res == null || res.length <= 0 ? 12.0 : res.length) + gap;
			}
			double cursor = -totalLength / 2.0;

			for (final Formation.Car car : formation.cars) {
				final VehicleResource res = VehiclePackLoader.getVehicle(car.vehicleId);
				if (res == null || res.models.isEmpty()) {
					cursor += 12.0 + gap;
					continue;
				}
				final double length = (res.length <= 0 ? 12.0 : res.length) + gap;

				poseStack.pushPose();
				// 车长沿 Z 轴（模型 z 范围 ±6 格），所以沿 Z 首尾相接排开；
				// 再加 0.5 格抬起，避免车底与屏幕平面共面导致 z-fighting。
				poseStack.translate(0, 0, cursor + length / 2.0);
				if (!car.forward) {
					poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180));
				}
				renderCar(poseStack, buffers, res);
				poseStack.popPose();

				cursor += length;
			}
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("formation preview render failed", t);
		}
	}

	private static void renderCar(PoseStack poseStack, MultiBufferSource buffers, VehicleResource res) {
		final var manager = Minecraft.getInstance().getResourceManager();
		for (final VehicleResource.ModelLayer layer : res.models) {
			if (layer.modelResource == null || layer.modelResource.isEmpty()) {
				continue; // props-only 层无几何
			}
			// [0.36.9] 用 ModelLayer 预计算的缓存键 / 预解析的 ResourceLocation
			final BbModelTree tree = BbModelTreeCache.tree(manager, layer.modelResource, layer.propertiesResource,
					layer.treeCacheKey);
			if (tree == null || tree.groupParts.isEmpty()) {
				continue;
			}
			final VertexConsumer vc = buffers.getBuffer(
					net.minecraft.client.renderer.RenderType.entityCutoutNoCull(
							BbModelTreeCache.texture(layer.textureLocation)));

			// [0.54.0] 删掉未使用的 GangwayProperties 加载：
			//   预览只画车体几何，不画贯通道/端墙（那是 FormationPreviewRenderer
			//   之外的世界渲染逻辑），此前每层每帧白做一次 properties 查找。
			// [0.38.0] 渲染计划快照（过滤 + 位定义查表在加载期完成；渲染输出不变）
			final LayerRenderCache plan = LayerRenderCache.load(
					manager, layer.propertiesResource, layer.positionDefinitionsResource);

			// 预览态：门完全打开（门开度 1.0，方向 opening=true），
			// 与上游 renderNormal 在「门全开」时的取值一致。
			final float previewDoorValue = 1.0F;
			final boolean previewOpening = true;
			final long now = System.currentTimeMillis();

			for (final LayerRenderCache.PlanPart planPart : plan.parts) {
				final Properties4xLoader.PartInfo info = planPart.info;
				// 上游 ModelPropertiesPart.java:339-345：门行程窗口外不渲染
				final boolean shouldRender = info.shouldRenderAt(previewDoorValue, previewOpening);
				// 上游 ModelPropertiesPart.java:317：闪烁是 per-part 的
				// （每条 wrapper 传自己的 flashOnTime/flashOffTime 给新建的
				//  ModelPropertiesPart 实例，见 ModelPropertiesPartWrapper.java:80-105）
				final boolean flashOn = info.flashOn(now);
				for (final String name : info.names) {
					final ModelPart anchor = tree.groupParts.get(name);
					if (anchor == null) {
						continue;
					}
					for (final PositionDefinitions4xLoader.PositionGroup group : planPart.groups) {
						// 预览：门按完全打开取值（time=1）
						for (final float[] p : group.positions) {
							drawInstance(poseStack, vc, anchor, info, false, p[0], p[1], p[2], shouldRender, flashOn);
						}
						for (final float[] p : group.positionsFlipped) {
							drawInstance(poseStack, vc, anchor, info, true, -p[0], p[1], p[2], shouldRender, flashOn);
						}
					}
				}
			}
		}
	}

	/** 预览态绘制一次实例（门完全打开） */
	private static void drawInstance(PoseStack poseStack, VertexConsumer vc, ModelPart anchor,
			Properties4xLoader.PartInfo info, boolean flipped, float px, float py, float pz,
			boolean shouldRender, boolean flashOn) {
		// [0.36.7] 用加载期预解析的枚举（原先每帧每部件重新 parse 字符串）
		final DoorAnimation4x anim = info.doorAnimation;
		final float dx = (info.doorXMultiplier == 0.0F && info.doorZMultiplier == 0.0F)
				? 0.0F : anim.getDoorAnimationX(info.doorXMultiplier, flipped, 1.0F);
		final float dz = (info.doorXMultiplier == 0.0F && info.doorZMultiplier == 0.0F)
				? 0.0F : anim.getDoorAnimationZ(info.doorZMultiplier, flipped, 1.0F, true);
		poseStack.pushPose();
		// 上游用 Integer.MAX_VALUE 把不该渲染的部件推离视野，而非跳过绘制
		float ox = (px + dx) / 16.0F;
		float oy = py / 16.0F;
		float oz = (pz + dz) / 16.0F;
		if (!shouldRender) {
			ox = Integer.MAX_VALUE;
			oz = Integer.MAX_VALUE;
		}
		if (!flashOn) {
			oy = Integer.MAX_VALUE;
		}
		poseStack.translate(ox, oy, oz);
		if (flipped) {
			poseStack.mulPose(com.mojang.math.Axis.YP.rotation((float) Math.PI));
		}
		anchor.render(poseStack, vc, 0xF000F0, OverlayTexture.NO_OVERLAY);
		poseStack.popPose();
	}
}

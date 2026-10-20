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

			int carIndex = 0;
			final int total = formation.cars.size();
			for (final Formation.Car car : formation.cars) {
				final VehicleResource res = VehiclePackLoader.getVehicle(car.vehicleId);
				if (res == null || res.models.isEmpty()) {
					carIndex++;
					continue;
				}

				poseStack.pushPose();
				poseStack.translate((carIndex - (total - 1) * 0.5) * 1.5, 0, 0);
				if (!car.forward) {
					poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180));
				}
				renderCar(poseStack, buffers, res);
				poseStack.popPose();

				carIndex++;
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
			final BbModelTree tree = BbModelTreeCache.tree(manager, layer.modelResource, layer.propertiesResource);
			if (tree == null || tree.groupParts.isEmpty()) {
				continue;
			}
			final VertexConsumer vc = buffers.getBuffer(
					net.minecraft.client.renderer.RenderType.entityCutoutNoCull(
							BbModelTreeCache.texture(layer.textureResource)));

			final Properties4xLoader.GangwayProperties props =
					Properties4xLoader.load(manager, layer.propertiesResource);
			final Map<String, PositionDefinitions4xLoader.PositionGroup> positions =
					PositionDefinitions4xLoader.load(manager, layer.positionDefinitionsResource);

			// 预览态：门完全打开（门开度 1.0，方向 opening=true），
			// 与上游 renderNormal 在「门全开」时的取值一致。
			final float previewDoorValue = 1.0F;
			final boolean previewOpening = true;
			final long now = System.currentTimeMillis();
			final boolean flashOn = flashOn(props.parts, now);

			for (final Properties4xLoader.PartInfo info : props.parts) {
				if (!"NORMAL".equals(info.type) || info.positionDefinitionNames.isEmpty()) {
					continue;
				}
				// 上游 ModelPropertiesPart.java:339-345：门行程窗口外不渲染
				final boolean shouldRender = info.shouldRenderAt(previewDoorValue, previewOpening);
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

	/**
	 * 层级闪烁判定（上游 {@code ModelPropertiesPart.java:317}）。
	 * 整层共享一份配置，取第一条带闪烁配置的部件。
	 */
	private static boolean flashOn(java.util.List<Properties4xLoader.PartInfo> parts, long now) {
		for (final Properties4xLoader.PartInfo info : parts) {
			if (info.flashOnTime + info.flashOffTime != 0) {
				return info.flashOn(now);
			}
		}
		return true;
	}

	/** 预览态绘制一次实例（门完全打开） */
	private static void drawInstance(PoseStack poseStack, VertexConsumer vc, ModelPart anchor,
			Properties4xLoader.PartInfo info, boolean flipped, float px, float py, float pz,
			boolean shouldRender, boolean flashOn) {
		final DoorAnimation4x anim = DoorAnimation4x.parse(info.doorAnimationType);
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

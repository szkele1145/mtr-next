package mtrnext.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtrnext.MtrNext;
import mtrnext.formation.Formation;
import mtrnext.formation.FormationStore;
import mtrnext.pack.VehiclePackLoader;
import mtrnext.pack.VehicleResource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;

import java.util.List;
import java.util.UUID;

/**
 * [0.29 适配] 编组屏 3D 预览——bbmodel 自定义盒逐面绘制版。
 * <p>
 * 旧实现经 ModelPart bake（per-face UV 缺陷）——与渲染器同步换到
 * BbBox 直绘。预览门盒固定偏移 -0.75F（门已打开态，露出车内）。
 * <p>
 * [0.34] 多模型层：遍历 {@code res.models} 全部层，每层按自己的
 * {@code textureResource} 取 buffer（调用方传 MultiBufferSource）。
 * 旧版只画第 0 层，5 层车包在预览里同样丢 4 层。
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
				final Matrix4f pose = poseStack.last().pose();
				// [0.34] 遍历全部模型层（与 BbModelTrainRenderer 同语义），
				// 每层各取自己贴图的 buffer；门盒按固定偏移 -0.75F 展示（预览视
				// 为门已打开，露出车内），非门盒 dz=0。
				for (final VehicleResource.ModelLayer layer : res.models) {
					final List<BbModelLoader.BbBox> boxes = BbModelLoader.load(
							Minecraft.getInstance().getResourceManager(), layer.modelResource);
					if (boxes == null || boxes.isEmpty()) {
						continue;
					}
					final VertexConsumer vc = buffers.getBuffer(
							net.minecraft.client.renderer.RenderType.entityCutoutNoCull(
									BbModelTrainRenderer.texLocation(layer.textureResource)));
					// 与主渲染器共享 winding，避免两处顶点顺序分道扬镳
					for (final BbModelLoader.BbBox box : boxes) {
						BbModelTrainRenderer.drawBoxFaces(vc, pose, box, box.isDoor ? -0.75F : 0.0F);
					}
				}
				poseStack.popPose();

				carIndex++;
			}
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("formation preview render failed", t);
		}
	}
}

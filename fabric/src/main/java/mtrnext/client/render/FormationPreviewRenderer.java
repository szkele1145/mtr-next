package mtrnext.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtrnext.MtrNext;
import mtrnext.formation.Formation;
import mtrnext.formation.FormationStore;
import mtrnext.pack.VehiclePackLoader;
import mtrnext.pack.VehicleResource;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.List;
import java.util.UUID;

/**
 * [0.29 适配] 编组屏 3D 预览——bbmodel 自定义盒逐面绘制版。
 * <p>
 * 旧实现经 ModelPart bake（per-face UV 缺陷）——与渲染器同步换到
 * BbBox 直绘。预览无门动画（静态展示）。
 */
public final class FormationPreviewRenderer {

	private FormationPreviewRenderer() {
	}

	public static void render(PoseStack poseStack, VertexConsumer vertexConsumer, UUID sidingKey) {
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
				final VehicleResource.ModelLayer layer = res.models.get(0);
				final List<BbModelLoader.BbBox> boxes = BbModelLoader.load(
						Minecraft.getInstance().getResourceManager(), layer.modelResource);
				if (boxes == null || boxes.isEmpty()) {
					carIndex++;
					continue;
				}

				poseStack.pushPose();
				poseStack.translate((carIndex - (total - 1) * 0.5) * 1.5, 0, 0);
				if (!car.forward) {
					poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180));
				}
				final Matrix4f pose = poseStack.last().pose();
				// 预览用已构造的 VertexConsumer（贴图取第一车）；与主渲染器共享 winding，避免两处顶点顺序分道扬镳
				for (final BbModelLoader.BbBox box : boxes) {
					BbModelTrainRenderer.drawBoxFaces(vertexConsumer, pose, box, 0.0F);
				}
				poseStack.popPose();

				carIndex++;
			}
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("formation preview render failed", t);
		}
	}

	/** 供调用方取贴图（VertexConsumer 由调用方以正确贴图构造） */
	public static ResourceLocation textureOf(Formation formation) {
		if (formation != null && !formation.cars.isEmpty()) {
			final VehicleResource res = VehiclePackLoader.getVehicle(formation.cars.get(0).vehicleId);
			if (res != null && !res.models.isEmpty()) {
				final String tex = res.models.get(0).textureResource;
				if (tex != null && !tex.isEmpty()) {
					final String[] parts = tex.split(":", 2);
					return parts.length == 2
							? new ResourceLocation(parts[0], parts[1])
							: new ResourceLocation("minecraft", parts[0]);
				}
			}
		}
		return new ResourceLocation("minecraft", "textures/block/white_concrete.png");
	}
}

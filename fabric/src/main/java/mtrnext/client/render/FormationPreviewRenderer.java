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
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * [编组预览渲染] 在编组编辑屏里把当前编组的车辆 3D 排布画出来。
 * <p>
 * 每车：ModelPart（bbmodel bake）平移到编组位（横向排开便于总览），
 * 方向翻转（头连头）用 Y 轴 180° 旋转。
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
				final VehicleResource.ModelLayer layer = res.models.get(0); // 第一层 = 车体
				final ModelPart model = BbModelLoader.bake(Minecraft.getInstance().getResourceManager(), layer.modelResource);
				if (model == null) {
					carIndex++;
					continue;
				}

				poseStack.pushPose();
				poseStack.translate((carIndex - (total - 1) * 0.5) * 1.5, 0, 0);
				if (!car.forward) {
					poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180));
				}
				// ModelPart.render(PoseStack, VertexConsumer, light, overlay) — 1.20.1 四参签名
				model.render(poseStack, vertexConsumer, 0xF000F0, 0);
				poseStack.popPose();

				carIndex++;
			}
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("formation preview render failed", t);
		}
	}

	private static ResourceLocation texLocation(String resource) {
		if (resource == null || resource.isEmpty()) {
			return new ResourceLocation("minecraft", "textures/block/white_concrete.png");
		}
		final String[] parts = resource.split(":", 2);
		return parts.length == 2
				? new ResourceLocation(parts[0], parts[1])
				: new ResourceLocation("minecraft", parts[0]);
	}

	/** 供调用方取贴图（VertexConsumer 由调用方以正确贴图构造） */
	public static ResourceLocation textureOf(Formation formation) {
		if (formation != null && !formation.cars.isEmpty()) {
			final VehicleResource res = VehiclePackLoader.getVehicle(formation.cars.get(0).vehicleId);
			if (res != null && !res.models.isEmpty()) {
				return texLocation(res.models.get(0).textureResource);
			}
		}
		return texLocation("");
	}
}

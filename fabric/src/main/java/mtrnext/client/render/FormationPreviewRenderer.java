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
				// 预览用已构造的 VertexConsumer（贴图取第一车）
				for (final BbModelLoader.BbBox box : boxes) {
					drawBox(vertexConsumer, pose, box, 0.0F);
				}
				poseStack.popPose();

				carIndex++;
			}
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("formation preview render failed", t);
		}
	}

	private static void drawBox(VertexConsumer vc, Matrix4f pose, BbModelLoader.BbBox box, float dz) {
		final float y1 = -box.y2, y2 = -box.y1;
		if (box.uvDown != null) face(vc, pose, box.x1, y2, box.z1 + dz, box.x2, y2, box.z1 + dz, box.x2, y2, box.z2 + dz, box.x1, y2, box.z2 + dz, box.uvDown);
		if (box.uvUp != null) face(vc, pose, box.x1, y1, box.z1 + dz, box.x2, y1, box.z1 + dz, box.x2, y1, box.z2 + dz, box.x1, y1, box.z2 + dz, box.uvUp);
		if (box.uvNorth != null) face(vc, pose, box.x1, y1, box.z1 + dz, box.x2, y1, box.z1 + dz, box.x2, y2, box.z1 + dz, box.x1, y2, box.z1 + dz, box.uvNorth);
		if (box.uvSouth != null) face(vc, pose, box.x1, y1, box.z2 + dz, box.x2, y1, box.z2 + dz, box.x2, y2, box.z2 + dz, box.x1, y2, box.z2 + dz, box.uvSouth);
		if (box.uvEast != null) face(vc, pose, box.x2, y1, box.z1 + dz, box.x2, y1, box.z2 + dz, box.x2, y2, box.z2 + dz, box.x2, y2, box.z1 + dz, box.uvEast);
		if (box.uvWest != null) face(vc, pose, box.x1, y1, box.z1 + dz, box.x1, y1, box.z2 + dz, box.x1, y2, box.z2 + dz, box.x1, y2, box.z1 + dz, box.uvWest);
	}

	private static void face(VertexConsumer vc, Matrix4f pose,
			float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz, float[] uv) {
		final float u1 = uv[0] / 24.0F, v1 = uv[1] / 24.0F, u2 = uv[2] / 24.0F, v2 = uv[3] / 24.0F;
		vc.vertex(pose, ax, ay, az).color(255, 255, 255, 255).uv(u1, v2).endVertex();
		vc.vertex(pose, bx, by, bz).color(255, 255, 255, 255).uv(u2, v2).endVertex();
		vc.vertex(pose, cx, cy, cz).color(255, 255, 255, 255).uv(u2, v1).endVertex();
		vc.vertex(pose, dx, dy, dz).color(255, 255, 255, 255).uv(u1, v1).endVertex();
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

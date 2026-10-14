package mtrnext.client.render;

import mtr.data.TrainClient;
import mtr.render.TrainRendererBase;
import mtrnext.MtrNext;
import mtrnext.pack.VehiclePackLoader;
import mtrnext.pack.VehicleResource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * [0.6] 4.0 车辆的 YMTR 渲染器（TrainRendererBase 子类）。
 * <p>
 * javap 完整抽象面：
 * <ul>
 *   <li>{@code createTrainInstance(TrainClient)}</li>
 *   <li>{@code renderCar(int, double×3, float×2, boolean×2)}</li>
 *   <li>{@code renderConnection(Vec3×8, double×3, float×2)} — 贯通道（8 顶点几何）</li>
 *   <li>{@code renderBarrier(Vec3×8, double×3, float×2)} — 端墙（8 顶点）</li>
 * </ul>
 * 基类静态渲染上下文：matrices(PoseStack) / vertexConsumers(MultiBufferSource)。
 * <p>
 * renderCar 用 BbModelLoader bake 的 ModelPart 画车体；connection/barrier
 * 0.6 留空实现（0.7 接 properties_*.json 程序化面）。
 */
public final class BbModelTrainRenderer extends TrainRendererBase {

	private final String trainId;

	public BbModelTrainRenderer(String trainId) {
		this.trainId = trainId;
	}

	@Override
	public TrainRendererBase createTrainInstance(TrainClient train) {
		return new BbModelTrainRenderer(trainId);
	}

	@Override
	public void renderCar(int carNumber, double x, double y, double z, float rotationY, float rotationZ, boolean doorLeftOpen, boolean doorRightOpen) {
		try {
			final VehicleResource res = VehiclePackLoader.getVehicle(trainId);
			if (res == null || res.models.isEmpty()) {
				return;
			}
			final VehicleResource.ModelLayer layer = res.models.get(0);
			final ModelPart model = BbModelLoader.bake(
					Minecraft.getInstance().getResourceManager(), layer.modelResource);
			if (model == null) {
				return;
			}
			final ResourceLocation tex = texLocation(layer.textureResource);
			final var vc = vertexConsumers.getBuffer(
					net.minecraft.client.renderer.RenderType.entityCutoutNoCull(tex));
			matrices.pushPose();
			matrices.translate(x, y, z);
			matrices.mulPose(com.mojang.math.Axis.YP.rotationDegrees(rotationY));
			matrices.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(rotationZ));
			model.render(matrices, vc, 0xF000F0, 0);
			matrices.popPose();
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("bbmodel renderCar failed", t);
		}
	}

	@Override
	public void renderConnection(Vec3 fromX, Vec3 fromZ, Vec3 toX, Vec3 toZ, Vec3 fromXFlip, Vec3 fromZFlip, Vec3 toXFlip, Vec3 toZFlip, double y, double width, double height, float angleX, float angleZ) {
		// 0.6: 贯通道留空（0.7 接 properties_*.json 四向贴图程序化面）
	}

	@Override
	public void renderBarrier(Vec3 fromX, Vec3 fromZ, Vec3 toX, Vec3 toZ, Vec3 fromXFlip, Vec3 fromZFlip, Vec3 toXFlip, Vec3 toZFlip, double y, double width, double height, float angleX, float angleZ) {
		// 0.6: 端墙留空（0.7 接）
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
}

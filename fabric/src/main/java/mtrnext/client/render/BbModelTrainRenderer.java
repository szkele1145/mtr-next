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
		// [0.7] 贯通道程序化渲染：properties_*.json 的 gangway 贴图 + 高度
		try {
			final VehicleResource res = VehiclePackLoader.getVehicle(trainId);
			if (res == null || res.models.isEmpty()) {
				return;
			}
			final VehicleResource.ModelLayer layer = res.models.get(0);
			if (layer.propertiesResource.isEmpty()) {
				return;
			}
			final Properties4xLoader.GangwayProperties props =
					Properties4xLoader.load(Minecraft.getInstance().getResourceManager(), layer.propertiesResource);
			drawConnectionFace(fromX, fromZ, toX, toZ, fromXFlip, fromZFlip, toXFlip, toZFlip,
					y, angleX, angleZ,
					props.gangwayInnerSide, props.gangwayHeight);
			// [0.9.2] 顶/底封板：gangwayInnerTop/Bottom 贴图的水平横面
			drawConnectionCap(fromX, fromZ, toX, toZ, y, props.gangwayInnerTop);
			drawConnectionCap(fromXFlip, fromZFlip, toXFlip, toZFlip, y, props.gangwayInnerTop);
			drawConnectionCap(fromX, toX, fromZ, toZ, y - props.gangwayHeight, props.gangwayInnerBottom);
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("renderConnection failed", t);
		}
	}

	@Override
	public void renderBarrier(Vec3 fromX, Vec3 fromZ, Vec3 toX, Vec3 toZ, Vec3 fromXFlip, Vec3 fromZFlip, Vec3 toXFlip, Vec3 toZFlip, double y, double width, double height, float angleX, float angleZ) {
		// [0.7] 端墙程序化渲染：barrier 贴图组
		try {
			final VehicleResource res = VehiclePackLoader.getVehicle(trainId);
			if (res == null || res.models.isEmpty()) {
				return;
			}
			final VehicleResource.ModelLayer layer = res.models.get(0);
			if (layer.propertiesResource.isEmpty()) {
				return;
			}
			final Properties4xLoader.GangwayProperties props =
					Properties4xLoader.load(Minecraft.getInstance().getResourceManager(), layer.propertiesResource);
			drawConnectionFace(fromX, fromZ, toX, toZ, fromXFlip, fromZFlip, toXFlip, toZFlip,
					y, angleX, angleZ,
					props.barrierInnerSide, props.barrierHeight);
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("renderBarrier failed", t);
		}
	}

	/**
	 * 在基类给的 8 顶点框架内画连接面（侧面贴图两条带 + 顶/底横面）。
	 */
	private void drawConnectionFace(Vec3 fromX, Vec3 fromZ, Vec3 toX, Vec3 toZ,
			Vec3 fromXFlip, Vec3 fromZFlip, Vec3 toXFlip, Vec3 toZFlip,
			double y, float angleX, float angleZ, String sideTex, float faceHeight) {
		if (sideTex == null || sideTex.isEmpty()) {
			return;
		}
		final var vc = vertexConsumers.getBuffer(
				net.minecraft.client.renderer.RenderType.entityCutoutNoCull(texLocation(sideTex)));
		final var pose = matrices.last().pose();
		final var normal = matrices.last().normal();
		// 正向边带: fromX→toZ 底边, 顶部 = y(基类给的连接面顶部高度)
		quad(vc, pose, normal, fromX, toZ, y, faceHeight);
		// 翻转边带
		quad(vc, pose, normal, fromXFlip, toZFlip, y, faceHeight);
	}

	/**
	 * [0.7.1] 顶/底横面：车端连接面的水平封板（top/bottom 贴图）。
	 * 顶点：fromX→toX（或翻转组）在 y 高度上的水平 quad。
	 */
	private void drawConnectionCap(Vec3 a, Vec3 b, Vec3 c, Vec3 d, double capY, String capTex) {
		if (capTex == null || capTex.isEmpty()) {
			return;
		}
		final var vc = vertexConsumers.getBuffer(
				net.minecraft.client.renderer.RenderType.entityCutoutNoCull(texLocation(capTex)));
		final var pose = matrices.last().pose();
		vc.vertex(pose, (float) a.x, (float) capY, (float) a.z).color(255, 255, 255, 255).uv(0, 0).endVertex();
		vc.vertex(pose, (float) b.x, (float) capY, (float) b.z).color(255, 255, 255, 255).uv(1, 0).endVertex();
		vc.vertex(pose, (float) c.x, (float) capY, (float) c.z).color(255, 255, 255, 255).uv(1, 1).endVertex();
		vc.vertex(pose, (float) d.x, (float) capY, (float) d.z).color(255, 255, 255, 255).uv(0, 1).endVertex();
	}

	private void quad(com.mojang.blaze3d.vertex.VertexConsumer vc,
			org.joml.Matrix4f pose, org.joml.Matrix3f normal,
			Vec3 left, Vec3 right, double topY, double h) {
		final float lx = (float) left.x, lz = (float) left.z;
		final float rx = (float) right.x, rz = (float) right.z;
		final float by = (float) (topY - h);
		vc.vertex(pose, lx, by, lz).color(255, 255, 255, 255).uv(0, 1).endVertex();
		vc.vertex(pose, rx, by, rz).color(255, 255, 255, 255).uv(1, 1).endVertex();
		vc.vertex(pose, rx, (float) topY, rz).color(255, 255, 255, 255).uv(1, 0).endVertex();
		vc.vertex(pose, lx, (float) topY, lz).color(255, 255, 255, 255).uv(0, 0).endVertex();
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

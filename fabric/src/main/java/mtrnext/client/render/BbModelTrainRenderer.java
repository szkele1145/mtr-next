package mtrnext.client.render;

import mtr.data.TrainClient;
import mtr.render.TrainRendererBase;
import mtrnext.MtrNext;
import mtrnext.pack.VehiclePackLoader;
import mtrnext.pack.VehicleResource;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

/**
 * [0.29 重构] 4.0 车辆渲染器——bbmodel 自定义盒的逐面绘制。
 * <p>
 * 旧实现走 MC ModelPart bake（per-face UV 语义不兼容, docs/bbox-uv-flaw.md）。
 * 新实现直接用 {@link BbModelLoader.BbBox} 列表 + 底层 quad 绘制：
 * 每面独立 UV、无重叠盒、无 6 倍顶点。门盒按门参数整体 Z 偏移（平滑动画）。
 * <p>
 * 顶点变换链（与 drawConnectionFace 同基础）：世界矩阵 → 车体平移/旋转 →
 * 盒旋转(origin 欧拉) → quad。
 */
public final class BbModelTrainRenderer extends TrainRendererBase {

	private final String trainId;

	/** [0.29] 门动画状态：每渲染器实例一个（=每列车实例） */
	private float doorSlide = 0.0F;

	/** 供 TrainTypeBridge 注册使用的公开构造器 */
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
			final List<BbModelLoader.BbBox> boxes = BbModelLoader.load(
					Minecraft.getInstance().getResourceManager(), layer.modelResource);
			if (boxes == null || boxes.isEmpty()) {
				return;
			}
			final ResourceLocation tex = texLocation(layer.textureResource);
			final var vc = vertexConsumers.getBuffer(
					net.minecraft.client.renderer.RenderType.entityCutoutNoCull(tex));

			// [0.29] 门平滑推进（与旧 DoorAnimator 同速率：开 0.5/s 关 0.8/s）
			final float delta = Minecraft.getInstance().getDeltaFrameTime() / 20.0F;
			final boolean open = doorLeftOpen || doorRightOpen;
			if (doorSlide < (open ? 1.0F : 0.0F)) {
				doorSlide = Math.min(open ? 1.0F : 0.0F, doorSlide + 0.5F * delta);
			} else if (doorSlide > (open ? 1.0F : 0.0F)) {
				doorSlide = Math.max(open ? 1.0F : 0.0F, doorSlide - 0.8F * delta);
			}
			final float doorOffset = -0.75F * doorSlide;

			matrices.pushPose();
			matrices.translate(x, y, z);
			matrices.mulPose(com.mojang.math.Axis.YP.rotationDegrees(rotationY));
			matrices.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(rotationZ));
			final Matrix4f pose = matrices.last().pose();

			for (final BbModelLoader.BbBox box : boxes) {
				final float dz = box.isDoor ? doorOffset : 0.0F;
				drawBoxFaces(vc, pose, box, dz);
			}
			matrices.popPose();
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("bbmodel renderCar failed", t);
		}
	}

	/** 逐面绘制一盒六面（每面独立 bbmodel UV；缺 UV 面跳过）
	 * [0.30] winding 核对：从外部看每面取逆时针序（MC 正面规则）。
	 * 北面(Z-)、南面(Z+)、西面(X-)已对；东面(X+)原序为顺时针(从 -X 看)
	 * → 交换对角顶点修正为从 +X 看逆时针。down/up 双面各按地面/顶视
	 * 观察方向逆时针。UV 对应随顶点交换同步调整。 */
	/** 逐面绘制一盒六面（每面独立 bbmodel UV；缺 UV 面跳过）。
	 * [0.30] winding 核对：从外部看每面取逆时针序（MC 正面规则）。
	 * 北面(Z-)、南面(Z+)、西面(X-)已对；东面(X+)原序为顺时针(从 -X 看)
	 * → 交换对角顶点修正为从 +X 看逆时针。down/up 双面各按地面/顶视
	 * 观察方向逆时针。UV 对应随顶点交换同步调整。
	 * 与 FormationPreviewRenderer 共享此方法，避免两处 winding 逻辑分道扬镳。 */
	static void drawBoxFaces(com.mojang.blaze3d.vertex.VertexConsumer vc, Matrix4f pose, BbModelLoader.BbBox box, float dz) {
		// bbmodel Y 向下 → MC Y 向上的翻转由 from/to 交换 y 实现
		final float y1 = -box.y2, y2 = -box.y1;

		if (box.uvDown != null) {
			// down 面：从下方看逆时针
			quad(vc, pose,
					box.x1, y2, box.z2 + dz, box.x2, y2, box.z2 + dz, box.x2, y2, box.z1 + dz, box.x1, y2, box.z1 + dz,
					box.uvDown);
		}
		if (box.uvUp != null) {
			// up 面：从上方看逆时针
			quad(vc, pose,
					box.x1, y1, box.z1 + dz, box.x2, y1, box.z1 + dz, box.x2, y1, box.z2 + dz, box.x1, y1, box.z2 + dz,
					box.uvUp);
		}
		if (box.uvNorth != null) {
			// north (Z-)：从 -Z 方向看逆时针
			quad(vc, pose,
					box.x1, y2, box.z1 + dz, box.x2, y2, box.z1 + dz, box.x2, y1, box.z1 + dz, box.x1, y1, box.z1 + dz,
					box.uvNorth);
		}
		if (box.uvSouth != null) {
			// south (Z+)：从 +Z 方向看逆时针
			quad(vc, pose,
					box.x1, y1, box.z2 + dz, box.x2, y1, box.z2 + dz, box.x2, y2, box.z2 + dz, box.x1, y2, box.z2 + dz,
					box.uvSouth);
		}
		if (box.uvEast != null) {
			// east (X+)：从 +X 方向看逆时针
			quad(vc, pose,
					box.x2, y1, box.z2 + dz, box.x2, y1, box.z1 + dz, box.x2, y2, box.z1 + dz, box.x2, y2, box.z2 + dz,
					box.uvEast);
		}
		if (box.uvWest != null) {
			// west (X-)：从 -X 方向看逆时针
			quad(vc, pose,
					box.x1, y1, box.z1 + dz, box.x1, y1, box.z2 + dz, box.x1, y2, box.z2 + dz, box.x1, y2, box.z1 + dz,
					box.uvWest);
		}
	}

	/** 一面 quad：bbmodel UV 归一化到 24×24 贴图（u1,v1,u2,v2 → 0~1）
	 * [0.30] 参数表随 winding 修正同步：v 分量按"y 大 = v 小"（贴图 V 向下） */
	static void quad(com.mojang.blaze3d.vertex.VertexConsumer vc, Matrix4f pose,
			float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz,
			float[] uv) {
		final float u1 = uv[0] / 24.0F, v1 = uv[1] / 24.0F, u2 = uv[2] / 24.0F, v2 = uv[3] / 24.0F;
		vc.vertex(pose, ax, ay, az).color(255, 255, 255, 255).uv(u1, v2).endVertex();
		vc.vertex(pose, bx, by, bz).color(255, 255, 255, 255).uv(u2, v2).endVertex();
		vc.vertex(pose, cx, cy, cz).color(255, 255, 255, 255).uv(u2, v1).endVertex();
		vc.vertex(pose, dx, dy, dz).color(255, 255, 255, 255).uv(u1, v1).endVertex();
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
			// [0.9.2] 顶/底封板
			drawConnectionCap(fromX, fromZ, toX, toZ, y, props.gangwayInnerTop);
			drawConnectionCap(fromXFlip, fromZFlip, toXFlip, toZFlip, y, props.gangwayInnerTop);
			drawConnectionCap(fromX, toX, fromZ, toZ, y - props.gangwayHeight, props.gangwayInnerBottom);
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("renderConnection failed", t);
		}
	}

	@Override
	public void renderBarrier(Vec3 fromX, Vec3 fromZ, Vec3 toX, Vec3 toZ, Vec3 fromXFlip, Vec3 fromZFlip, Vec3 toXFlip, Vec3 toZFlip, double y, double width, double height, float angleX, float angleZ) {
		// [0.7] 端墙程序化渲染
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
	 * 连接面侧面贴图带（正/反两条）
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
		connectionQuad(vc, pose, fromX, toZ, y, faceHeight);
		connectionQuad(vc, pose, fromXFlip, toZFlip, y, faceHeight);
	}

	/** [0.9.2] 顶/底横面：车端连接面的水平封板 */
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

	private void connectionQuad(com.mojang.blaze3d.vertex.VertexConsumer vc, Matrix4f pose,
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

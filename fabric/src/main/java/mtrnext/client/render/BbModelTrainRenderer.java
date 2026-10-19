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
			// [0.29] 门平滑推进（开 0.5/s, 关 0.8/s）
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

			// [0.34] 遍历全部模型层。4.0 的 models[] 是"叠放图层"而非变体互斥：
			// 实测 ERIDU-DG1000A 头车 5 层 = 1 个 props-only 层（modelResource 空，
			// 仅承载整车 gangway/barrier 的 properties）+ 车体 + LCD + 驾驶面板 + 高峰,
			// 各层贴图独立。旧实现只取 models.get(0)，多层车包实际只渲染第 0 层
			//（5 层丢 4 层）。每层自带贴图 → 各取自己的 RenderType/VertexConsumer，
			// 缺层跳过不影响其余层。props-only 层由 BbModelLoader.load 的空串
			// 短路返回空盒表，不会触发 ResourceLocation("") 异常刷屏。
			for (final VehicleResource.ModelLayer layer : res.models) {
				// [0.35] 门动画需要 parts[]（门倍率/动画型），故用双键缓存的
				// loadAnnotated（模型 + 属性）——同一 bbmodel 可被不同 properties
				// 引用，单键缓存会串门参数。
				final List<BbModelLoader.BbBox> boxes = BbModelLoader.loadAnnotated(
						Minecraft.getInstance().getResourceManager(), layer.modelResource, layer.propertiesResource);
				if (boxes == null || boxes.isEmpty()) {
					continue;
				}
				final var vc = vertexConsumers.getBuffer(
						net.minecraft.client.renderer.RenderType.entityCutoutNoCull(texLocation(layer.textureResource)));
				for (final BbModelLoader.BbBox box : boxes) {
					// [0.35] 门部件按上游 DoorAnimationType 曲线位移：
					// 倍率单位 = Blockbench 像素（16 像素 = 1 格），曲线值
					// × 倍率后 /16 转格。X 走塞拉门外摆，Z 走滑门滑动。
					// 非门部件沿用旧的固定 Z 偏移（3.x 语义，无倍率可依）。
					if (box.isAnimatedDoor()) {
						// flipped 恒 false：mtr-next 尚未实现上游 positionDefinitions
						// 的 positionsFlipped 位置组（上游 ModelPropertiesPart
						// L649-650 会整体镜像 X/Z 并把 flipped 传进曲线）。
						// bbmodel 自身不含朝向信息，故此处按未镜像处理。
						final float dx = box.doorAnim.getDoorAnimationX(box.doorXMult, false, doorSlide) / 16.0F;
						final float dz = box.doorAnim.getDoorAnimationZ(box.doorZMult, false, doorSlide, open) / 16.0F;
						drawBoxFaces(vc, pose, box, dx, dz);
					} else {
						drawBoxFaces(vc, pose, box, box.isDoor ? doorOffset : 0.0F);
					}
				}
			}
			matrices.popPose();
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("bbmodel renderCar failed", t);
		}
	}

	/** 逐面绘制一盒六面（每面独立 bbmodel UV；缺 UV 面跳过）。
	 * [0.30] winding 核对：从外部看每面取逆时针序（MC 正面规则）。
	 * 北面(Z-)、南面(Z+)、西面(X-)已对；东面(X+)原序为顺时针(从 -X 看)
	 * → 交换对角顶点修正为从 +X 看逆时针。down/up 双面各按地面/顶视
	 * 观察方向逆时针。UV 对应随顶点交换同步调整。
	 * 与 FormationPreviewRenderer 共享此方法，避免两处 winding 逻辑分道扬镳。
	 * [0.35.2] {@code flipTextureV} 语义已按上游定论：上游 VehicleModel.createModel
	 * 只把它传给 .obj/.mqo 路径（ModelResourceLoader.loadModel），bbmodel 路径
	 * （DynamicVehicleModel 的 BlockbenchModel 构造器）完全不接收——即上游对
	 * bbmodel 同样不消费该字段（Blockbench 导出的 UV 原点本就与 MC 一致，
	 * 无需翻转）。mtr-next 不实装该字段 = 与上游行为一致，非遗漏。 */
	static void drawBoxFaces(com.mojang.blaze3d.vertex.VertexConsumer vc, Matrix4f pose, BbModelLoader.BbBox box, float dz) {
		drawBoxFaces(vc, pose, box, 0.0F, dz);
	}

	/**
	 * [0.35] 逐面绘制一盒六面，支持 X/Z 双轴位移（门动画的横向外摆 + 纵向滑动）。
	 *
	 * @param dx X 轴位移（格）。塞拉门（PLUG_*）沿 X 外摆，非塞拉门为 0。
	 * @param dz Z 轴位移（格）。滑门沿车长方向滑动。
	 */
	static void drawBoxFaces(com.mojang.blaze3d.vertex.VertexConsumer vc, Matrix4f pose, BbModelLoader.BbBox box, float dx, float dz) {
		// bbmodel Y 向下 → MC Y 向上的翻转由 from/to 交换 y 实现
		final float y1 = -box.y2, y2 = -box.y1;
		// 门位移：整盒平移（顶点 + 偏移量）
		final float x1 = box.x1 + dx, x2 = box.x2 + dx;
		final float z1 = box.z1 + dz, z2 = box.z2 + dz;

		if (box.uvDown != null) {
			// down 面：从下方看逆时针
			quad(vc, pose,
					x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1,
					box.uvDown, box.uvScaleU, box.uvScaleV);
		}
		if (box.uvUp != null) {
			// up 面：从上方看逆时针
			quad(vc, pose,
					x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2,
					box.uvUp, box.uvScaleU, box.uvScaleV);
		}
		if (box.uvNorth != null) {
			// north (Z-)：从 -Z 方向看逆时针
			quad(vc, pose,
					x1, y2, z1, x2, y2, z1, x2, y1, z1, x1, y1, z1,
					box.uvNorth, box.uvScaleU, box.uvScaleV);
		}
		if (box.uvSouth != null) {
			// south (Z+)：从 +Z 方向看逆时针
			quad(vc, pose,
					x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2,
					box.uvSouth, box.uvScaleU, box.uvScaleV);
		}
		if (box.uvEast != null) {
			// east (X+)：从 +X 方向看逆时针
			quad(vc, pose,
					x2, y1, z2, x2, y1, z1, x2, y2, z1, x2, y2, z2,
					box.uvEast, box.uvScaleU, box.uvScaleV);
		}
		if (box.uvWest != null) {
			// west (X-)：从 -X 方向看逆时针
			quad(vc, pose,
					x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1,
					box.uvWest, box.uvScaleU, box.uvScaleV);
		}
	}

	/** 一面 quad：bbmodel UV 归一化到贴图（u1,v1,u2,v2 → 0~1）
	 * [0.30] 参数表随 winding 修正同步：v 分量按"y 大 = v 小"（贴图 V 向下）
	 * [0.35] 归一化除数改为 per-box 的 bbmodel resolution（1/resW、1/resH）——
	 * 旧实现硬编码 24（MC 方块贴图边长），对 bbmodel 完全错误：实测 ERIDU
	 * 四个模型 resolution 为 512/128/24/64，除 24 会让 UV 越界（车体
	 * maxU=384 > 24）→ 贴图错位。resolution 由 BbModelLoader 解析层给出。
	 */
	static void quad(com.mojang.blaze3d.vertex.VertexConsumer vc, Matrix4f pose,
			float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz,
			float[] uv, float scaleU, float scaleV) {
		final float u1 = uv[0] * scaleU, v1 = uv[1] * scaleV, u2 = uv[2] * scaleU, v2 = uv[3] * scaleV;
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
			// 贯通道/端墙属性是"每车"而非"每层"语义（4.0 properties_*.json
			// 描述整车 gangway/barrier），故只读第 0 层的 propertiesResource——
			// 与上面的多模型层循环不同，这里不是遗漏。
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
			// 同 renderConnection：端墙属性按"每车"取，固定第 0 层
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

	/** 车包贴图字符串 → ResourceLocation（供本类与 FormationPreviewRenderer 共用）。
	 * 缺省/空回退白色混凝土，避免 entityCutoutNoCull 拿到空贴图。 */
	static ResourceLocation texLocation(String resource) {
		if (resource == null || resource.isEmpty()) {
			return new ResourceLocation("minecraft", "textures/block/white_concrete.png");
		}
		final String[] parts = resource.split(":", 2);
		return parts.length == 2
				? new ResourceLocation(parts[0], parts[1])
				: new ResourceLocation("minecraft", parts[0]);
	}
}

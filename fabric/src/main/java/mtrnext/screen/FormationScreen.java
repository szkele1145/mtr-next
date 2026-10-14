package mtrnext.screen;

import mtrnext.MtrNext;
import mtrnext.client.MtrNextClient;
import mtrnext.formation.Formation;
import mtrnext.formation.FormationStore;
import mtrnext.pack.VehiclePackLoader;
import mtrnext.pack.VehicleResource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [编组编辑屏] 原版控件实现（4.x 用 Elementa UI 无法搬运，语义对齐
 * VehicleSelectorScreen：可用车列表 / 已选编组列表 / 头连头方向翻转）。
 * <p>
 * 布局（零依赖原版控件）：
 * <pre>
 *  ┌────────────────────────────────────────┐
 *  │ MTR-Next Formation   [当前编组: N 节, L 格]│
 *  │ ┌─可用车(4.0)─┐  ┌─编组序列────────┐   │
 *  │ │ ERIDU(M_F) │  │ 1. M_F →   [翻转]│   │
 *  │ │ ERIDU(M_T) │  │ 2. M_T →   [删除]│   │
 *  │ │ ERIDU(M_B) │  │ ...              │   │
 *  │ │  [加到编组] │  │                  │   │
 *  │ └────────────┘  └──────────────────┘   │
 *  │              [保存并关闭]                │
 *  └────────────────────────────────────────┘
 * </pre>
 * 连接面约束经 {@link Formation#canAddCar}——头连头（方向翻转）在加车时
 * 自动带出方向按钮。
 * <p>
 * 打开入口：暂为屏幕类公开（0.2 接键盘快捷键 / SidingScreen 注入按钮）。
 */
public final class FormationScreen extends Screen {

	private final Screen parent;
	private final UUID sidingId;
	private final Formation formation;

	private final List<VehicleResource> available = new ArrayList<>();
	private int selectedAvailable = -1;

	private static final int LIST_W = 140;
	private static final int ROW_H = 22;

	public FormationScreen(Screen parent, UUID sidingId) {
		super(Component.literal("MTR-Next Formation"));
		this.parent = parent;
		this.sidingId = sidingId;
		this.formation = FormationStore.getOrCreate(sidingId);
	}

	@Override
	protected void init() {
		available.clear();
		for (Map.Entry<String, VehicleResource> e : VehiclePackLoader.getVehicles().entrySet()) {
			available.add(e.getValue());
		}
		rebuild();
	}

	private void rebuild() {
		clearWidgets();

		final int x0 = this.width / 2 - (LIST_W * 2 + 20) / 2;
		final int y0 = 40;

		// 左列：可用车
		int y = y0;
		for (int i = 0; i < available.size() && i < 8; i++) {
			final VehicleResource res = available.get(i);
			final int idx = i;
			addRenderableWidget(Button.builder(
							Component.literal((i == selectedAvailable ? "> " : "  ") + shortName(res.id)),
							b -> {
								selectedAvailable = idx;
								rebuild();
							})
					.bounds(x0, y, LIST_W, ROW_H - 2).build());
			y += ROW_H;
		}

		// "加到编组"（默认正向；连接面校验失败时提示）
		addRenderableWidget(Button.builder(Component.literal("Add >>"), b -> {
			if (selectedAvailable >= 0 && selectedAvailable < available.size()) {
				final VehicleResource res = available.get(selectedAvailable);
				if (formation.canAddCar(formation.cars.size(), res, true)) {
					formation.cars.add(new Formation.Car(res.id, true));
					rebuild();
				}
			}
		}).bounds(x0, Math.min(y + 4, this.height - 90), LIST_W, 20).build());

		// 右列：编组序列（含每节的方向翻转/删除）
		final int x1 = x0 + LIST_W + 20;
		y = y0;
		for (int i = 0; i < formation.cars.size() && i < 8; i++) {
			final Formation.Car car = formation.cars.get(i);
			final int idx = i;
			addRenderableWidget(Button.builder(
							Component.literal(String.format("%d. %s %s", idx + 1, shortName(car.vehicleId), car.forward ? "→" : "←")),
							b -> { /* 序列行：点击无操作（信息行） */ })
					.bounds(x1, y, LIST_W - 52, ROW_H - 2).build());
			addRenderableWidget(Button.builder(Component.literal(car.forward ? "→" : "←"), b -> {
				// 头连头：方向翻转
				final Formation.Car c = formation.cars.get(idx);
				formation.cars.set(idx, new Formation.Car(c.vehicleId, !c.forward));
				rebuild();
			}).bounds(x1 + LIST_W - 50, y, 24, ROW_H - 2).build());
			addRenderableWidget(Button.builder(Component.literal("X"), b -> {
				formation.cars.remove(idx);
				rebuild();
			}).bounds(x1 + LIST_W - 24, y, 24, ROW_H - 2).build());
			y += ROW_H;
		}

		// [0.9.3] 清空编组（右列下方，编组非空时可用）
		if (!formation.cars.isEmpty()) {
			addRenderableWidget(Button.builder(Component.literal("Clear All"), b -> {
				formation.cars.clear();
				rebuild();
			}).bounds(x1, Math.min(y + 4, this.height - 90), LIST_W, 18).build());
		}

		// 保存并关闭（同时把编组应用到 YMTR 的 Siding 车辆配置）
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
				.bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
	}

	@Override
	public void onClose() {
		FormationStore.put(sidingId, formation);
		// [0.4] 编组 → Siding 车辆配置桥接（baseTrainType/trainCars）
		final Object hostSiding = mtrnext.util.HostSidingHolder.consume();
		if (hostSiding != null) {
			final int applied = mtrnext.formation.FormationSidingBridge.applyToSiding(hostSiding, formation);
			if (applied > 0) {
				MtrNext.LOGGER.info("Applied formation ({} cars) to YMTR siding", applied);
			}
		}
		Minecraft.getInstance().setScreen(parent);
	}

	private static String shortName(String id) {
		return id.length() > 16 ? id.substring(0, 14) + ".." : id;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		renderBackground(guiGraphics);
		super.render(guiGraphics, mouseX, mouseY, delta);
		guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFFFFF);
		guiGraphics.drawCenteredString(this.font,
				Component.literal(String.format("%d cars, %.1f blocks total", formation.cars.size(), formation.getTotalLength())),
				this.width / 2, 30, 0xAAAAFF);

		// [0.7.1] 可用车列表的车色图标条：每个按钮左侧画该车 color 的色块
		int iconY = 40;
		for (int i = 0; i < available.size() && i < 8; i++) {
			final VehicleResource res = available.get(i);
			final int c = res.color | 0xFF000000;
			final int xIcon = this.width / 2 - (LIST_W * 2 + 20) / 2 + 2;
			guiGraphics.fill(xIcon, iconY + 3, xIcon + 6, iconY + ROW_H - 5, c);
			guiGraphics.renderOutline(xIcon, iconY + 3, 6, ROW_H - 8, 0xFF000000);
			iconY += ROW_H;
		}

		// [0.2] 屏内 3D 编组预览：编组列车横向排开（俯视 35° 视角，缩放按编组长自适应）
		if (!formation.cars.isEmpty()) {
			try {
				final com.mojang.blaze3d.vertex.PoseStack pose = guiGraphics.pose();
				pose.pushPose();
				// 视角：屏幕下三分之一处为原点，等距俯视
				final float scale = (float) Math.min(0.8, 6.0 / Math.max(1, formation.cars.size()));
				pose.translate(this.width / 2.0, this.height * 0.72, 100.0);
				pose.scale(scale, -scale, scale); // GUI 坐标系 Y 翻转 → 模型正立
				pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(35)); // 俯视
				final var tex = mtrnext.client.render.FormationPreviewRenderer.textureOf(formation);
				final var vc = Minecraft.getInstance().renderBuffers().bufferSource()
						.getBuffer(net.minecraft.client.renderer.RenderType.entityCutoutNoCull(tex));
				mtrnext.client.render.FormationPreviewRenderer.render(pose, vc, sidingId);
				Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
				pose.popPose();
			} catch (final Throwable t) {
				MtrNext.LOGGER.debug("formation 3d preview failed", t);
			}
		}
	}

	public static void open(UUID sidingId) {
		Minecraft.getInstance().setScreen(new FormationScreen(
				Minecraft.getInstance().screen, sidingId));
		MtrNext.LOGGER.info("Formation screen opened for siding {}", sidingId);
	}
}

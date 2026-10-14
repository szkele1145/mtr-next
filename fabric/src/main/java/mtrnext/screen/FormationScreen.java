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
	/** [0.12] 可用车列表页码 */
	private int availablePage = 0;
	/** [0.13] 编组序列页码 */
	private int consistPage = 0;
	/** [0.9.4] 校验失败的可见反馈（2 秒超时） */
	private String rejectReason = null;
	private long rejectAt = 0;

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

		// 左列：可用车（[0.12] 分页 — 每页 8 个，车包超 8 种车时翻页浏览）
		int y = y0;
		final int pageStart = availablePage * 8;
		for (int i = pageStart; i < available.size() && i < pageStart + 8; i++) {
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
		// [0.12] 翻页控件
		final int totalPages = (available.size() + 7) / 8;
		if (totalPages > 1) {
			addRenderableWidget(Button.builder(Component.literal("<"), b -> {
				availablePage = (availablePage + totalPages - 1) % totalPages;
				rebuild();
			}).bounds(x0, y + 2, 30, 16).build());
			addRenderableWidget(Button.builder(Component.literal(">"), b -> {
				availablePage = (availablePage + 1) % totalPages;
				rebuild();
			}).bounds(x0 + LIST_W - 30, y + 2, 30, 16).build());
			addRenderableWidget(Button.builder(
					Component.literal((availablePage + 1) + "/" + totalPages), b -> { })
					.bounds(x0 + 32, y + 2, LIST_W - 64, 16).build());
		}

		// "加到编组"（默认正向；连接面校验失败时在 Add 按钮上提示）
		addRenderableWidget(Button.builder(Component.literal("Add >>"), b -> {
			if (selectedAvailable >= 0 && selectedAvailable < available.size()) {
				final VehicleResource res = available.get(selectedAvailable);
				if (formation.canAddCar(formation.cars.size(), res, true)) {
					formation.cars.add(new Formation.Car(res.id, true));
					rejectReason = null;
					rebuild();
				} else {
					// [0.9.4] 校验失败的可见反馈（此前静默无响应）
					rejectReason = "Can't couple: no compatible face / not same family";
					rejectAt = System.currentTimeMillis();
					rebuild();
				}
			}
		}).bounds(x0, Math.min(y + 4, this.height - 90), LIST_W, 20).build());

		// [0.9.4] 校验失败提示行（Add 下方, 2 秒内有效）
		if (rejectReason != null && System.currentTimeMillis() - rejectAt < 2000) {
			addRenderableWidget(Button.builder(Component.literal(rejectReason), b -> { })
					.bounds(x0, Math.min(y + 26, this.height - 70), LIST_W, 16).build());
		}

		// 右列：编组序列（[0.13] 分页 — 编组超 8 节时翻页；含每节的方向翻转/删除）
		final int x1 = x0 + LIST_W + 20;
		y = y0;
		final int consistStart = consistPage * 8;
		for (int i = consistStart; i < formation.cars.size() && i < consistStart + 8; i++) {
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

		// [0.13] 编组序列翻页控件
		final int consistPages = (formation.cars.size() + 7) / 8;
		if (consistPages > 1) {
			addRenderableWidget(Button.builder(Component.literal("<"), b -> {
				consistPage = (consistPage + consistPages - 1) % consistPages;
				rebuild();
			}).bounds(x1, y + 2, 30, 16).build());
			addRenderableWidget(Button.builder(Component.literal(">"), b -> {
				consistPage = (consistPage + 1) % consistPages;
				rebuild();
			}).bounds(x1 + LIST_W - 30, y + 2, 30, 16).build());
			addRenderableWidget(Button.builder(
					Component.literal((consistPage + 1) + "/" + consistPages), b -> { })
					.bounds(x1 + 32, y + 2, LIST_W - 64, 16).build());
			y += 18;
		}

		// [0.9.3] 清空编组（右列下方，编组非空时可用）
		if (!formation.cars.isEmpty()) {
			addRenderableWidget(Button.builder(Component.literal("Clear All"), b -> {
				formation.cars.clear();
				consistPage = 0;
				rebuild();
			}).bounds(x1, Math.min(y + 4, this.height - 90), LIST_W, 18).build());
		}

		// [0.15] 反转全部（一键整列掉头 — 重联/折返场景）
		if (!formation.cars.isEmpty()) {
			addRenderableWidget(Button.builder(Component.literal("Flip All"), b -> {
				for (int i = 0; i < formation.cars.size(); i++) {
					final Formation.Car c = formation.cars.get(i);
					formation.cars.set(i, new Formation.Car(c.vehicleId, !c.forward));
				}
				rebuild();
			}).bounds(x1 + 60, Math.min(y + 4, this.height - 90), LIST_W - 60, 18).build());
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

		// [0.14] 选中车详情条：编组决策辅助（几何/连接面摘要）
		if (selectedAvailable >= 0 && selectedAvailable < available.size()) {
			final VehicleResource sel = available.get(selectedAvailable);
			final String faces = String.format("%s|%s",
					sel.hasGangway1 || sel.hasBarrier1 ? "1:Y" : "1:-",
					sel.hasGangway2 || sel.hasBarrier2 ? "Y" : "-");
			final String detail = String.format("%s  L=%.1f W=%.1f  faces[%s]  bogie %.0f/%.0f",
					shortName(sel.id), sel.length, sel.width, faces,
					sel.bogie1Position, sel.bogie2Position);
			guiGraphics.drawCenteredString(this.font,
					Component.literal(detail), this.width / 2, 62, 0x88FF88);
		}

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

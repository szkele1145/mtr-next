package mtrnext.screen;

import mtrnext.MtrNext;
import mtrnext.formation.Formation;
import mtrnext.formation.FormationStore;
import mtrnext.pack.VehiclePackLoader;
import mtrnext.pack.VehicleResource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [编组编辑屏] 原版控件实现（4.x 的 Elementa UI 无法搬运，语义对齐
 * VehicleSelectorScreen：可用车列表 / 已选编组列表 / 头连头方向翻转）。
 *
 * <h2>★★★ [0.60.0] 布局重构：全部坐标由「游标」递推，控件禁止相交 ★★★</h2>
 *
 * <p>旧实现的坐标是散落在 {@code rebuild()} 与 {@code render()} 里的魔法数：
 * 列表固定 8 行 / {@code y0=40} / {@code Math.min(y + 4, height - 56)}，
 * 于是「翻页行」和「添加 >>」在 480×270 下直接撞在一起，
 * 说明性文字又用 {@code drawString} 画在 y=62/74 —— 正好压在列表第一、二行按钮上。
 * 真机观感就是用户说的「字和按钮一大堆全都重叠，可辨认性为 0」。
 *
 * <p>本版把布局改成<b>自顶向下 + 自底向上双向夹逼</b>，所有 y 只在这里算一次，
 * 并存进 {@code layout*} 字段供 {@link #render} 复用（文字坐标不再另算一套）：
 *
 * <pre>
 *  元素           x              y                w                     h
 *  ────────────────────────────────────────────────────────────────────────
 *  标题           居中           16               文本                 10
 *  副标题         居中           28               文本                 10
 *  选中车详情     居中           40               文本                 10
 *  左表头         x0             54               LIST_W               10
 *  右表头         x1             54               LIST_W               10
 *  左行 i         x0             LIST_Y0+i*ROW_H  LIST_W               18
 *  右行 i         x1             同上             LIST_W-52            18
 *  右行翻转       x1+LIST_W-50   同上             24                   18
 *  右行删除       x1+LIST_W-24   同上             24                   18
 *  左翻页         x0             pagerY           LIST_W               16
 *  右翻页         x1             pagerY           LIST_W               16
 *  「添加 >>」    x0             addY             LIST_W               20
 *  「全部清空」   x1             flipY            W2                   20
 *  「全部翻转」   x1+W2+8        flipY            W2                   20
 *  提示行         x0             hintY            2*LIST_W+16          10
 *  「完成」      W/2-60         H-28             120                  20
 *  ────────────────────────────────────────────────────────────────────────
 *  LIST_Y0 = 66，ROW_H = 20，W2 = (LIST_W-8)/2
 *  listEndY = LIST_Y0 + maxRows*ROW_H
 *  maxRows  = clamp((H - LIST_Y0 - RESERVED) / ROW_H, 1, 8)
 *  RESERVED = 提示行14 + 翻转行22 + 添加行22 + 翻页行22 + 底部38
 * </pre>
 *
 * <p><b>自检</b>：{@link #rebuild()} 结尾调 {@link #assertNoOverlap()}，
 * 任何两个控件矩形相交都打 {@code [formation] 控件重叠} 的 warn。
 * 这条 warn 就是「界面又撞了」的客观证据，交付前必须是零条。
 *
 * <p><b>[0.60.0] 已彻底删除屏内 3D 列车预览</b>：{@code FormationPreviewRenderer}
 * 早就不再被调用，但 {@code render()} 尾部还留着 60 行注释 + 一段空跑的
 * {@code pushPose/translate/scale/mulPose/popPose}（没有任何渲染调用），
 * 既占屏又让人误以为界面上有预览。整块已删除。
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
	/** [0.9.4] 校验失败的可见反馈（3 秒超时） */
	private String rejectReason = null;
	private long rejectAt = 0;
	/** [0.56.0] 编辑锁是否由本界面持有（保证 beginEdit/endEdit 成对） */
	private boolean editLocked = false;

	// ---- [0.60.0] 布局结果（rebuild 里算，render 里读；绝不各算一套）----
	private int layoutX0;
	private int layoutX1;
	private int layoutListY0;
	private int layoutMaxRows;
	private int layoutPagerY;
	private int layoutAddY;
	private int layoutFlipY;
	private int layoutHintY;
	/** rebuild 期间登记的全部控件（供重叠自检；不依赖 Screen 的内部字段名） */
	private final List<AbstractWidget> layoutWidgets = new ArrayList<>();

	private static final int LIST_W = 150;
	private static final int ROW_H = 20;
	private static final int ROW_BTN_H = 18;
	private static final int GAP = 16;

	/** 列表内容起始 y（表头 54 + 12） */
	private static final int LIST_Y0 = 66;
	/** 列表区以下、必须在底部预留的总高度：翻页行 + 添加行 + 翻转行 + 提示行 + 完成按钮 */
	private static final int RESERVED = 22 + 22 + 22 + 14 + 38;

	public FormationScreen(Screen parent, UUID sidingId) {
		super(Component.literal("MTR-Next 编组"));
		this.parent = parent;
		this.sidingId = sidingId;
		this.formation = FormationStore.getOrCreate(sidingId);
		// ★ [0.56.0] 编辑锁：界面开着时，FormationAutoSync 的「从磁盘重载」必须让路，
		//   否则进世界后 5～35 秒的任一重试都会把内存里刚加的编组清掉再读旧文件。
		FormationStore.beginEdit();
		this.editLocked = true;
		// ★ [0.57.0] 构造期日志：确认「界面拿到的编组实例」是不是空的孤儿对象。
		MtrNext.LOGGER.info("[formation] screen 构造: siding={} formation={} 内存节数={} store 条数={} editing={}",
				sidingId, this.formation == null ? "null" : Integer.toHexString(System.identityHashCode(this.formation)),
				this.formation == null ? -1 : this.formation.cars.size(),
				FormationStore.all().size(), FormationStore.isEditing());
	}

	@Override
	protected void init() {
		available.clear();
		for (Map.Entry<String, VehicleResource> e : VehiclePackLoader.getVehicles().entrySet()) {
			if (e.getValue() != null) {
				available.add(e.getValue());
			}
		}
		// ★ [0.60.0] 车包注册表是 ConcurrentHashMap（无序）⇒ 不排序的话每次打开
		//   列表顺序都可能不同，玩家会以为「车变了」。按 id 稳定排序。
		available.sort(java.util.Comparator.comparing(r -> r.id == null ? "" : r.id));
		// ★ [0.55.0] 默认选中第一辆车（否则「添加 >>」静默无反应）
		if (selectedAvailable < 0 && !available.isEmpty()) {
			selectedAvailable = 0;
		}
		if (selectedAvailable >= available.size()) {
			selectedAvailable = available.isEmpty() ? -1 : available.size() - 1;
		}
		MtrNext.LOGGER.info("[formation] init: 可用车={} 种（has4x={}）默认选中={} 编组节数={} 窗口={}x{}",
				available.size(), VehiclePackLoader.has4xVehicles(), selectedAvailable,
				formation.cars.size(), this.width, this.height);
		rebuild();
	}

	/**
	 * [0.60.0] 重算全部控件位置。
	 *
	 * <p>坐标推导只看这一个方法（顶部 javadoc 有布局表），{@link #render} 只读
	 * {@code layout*} 字段画文字 ⇒ 文字与按钮永远同一套坐标，不会再一个算出 62、
	 * 另一个算出 40。
	 */
	private void rebuild() {
		clearWidgets();
		layoutWidgets.clear();

		// ---- 水平：两列等宽居中 ----
		final int totalW = LIST_W * 2 + GAP;
		layoutX0 = (this.width - totalW) / 2;
		layoutX1 = layoutX0 + LIST_W + GAP;
		final int W2 = (LIST_W - 8) / 2;

		// ---- 垂直：先自底向上预留固定区，再把剩余高度给列表 ----
		final int doneY = this.height - 28;
		layoutHintY = doneY - 14;               // 提示行（文字 10px）
		layoutFlipY = layoutHintY - 22;         // 「全部清空 / 全部翻转」
		layoutAddY = layoutFlipY - 22;          // 「添加 >>」
		layoutPagerY = layoutAddY - 22;         // 两列翻页行

		// 列表能用到 layoutPagerY - 4 为止
		final int listBottomLimit = layoutPagerY - 4;
		layoutMaxRows = Math.max(1, Math.min(8, (listBottomLimit - LIST_Y0) / ROW_H));
		layoutListY0 = LIST_Y0;

		// ---- 左列：可用车（按 layoutMaxRows 分页）----
		final int perPage = layoutMaxRows;
		final int availPages = Math.max(1, (available.size() + perPage - 1) / perPage);
		availablePage = Math.min(Math.max(0, availablePage), availPages - 1);
		final int availStart = availablePage * perPage;
		int y = layoutListY0;
		for (int i = availStart; i < available.size() && i < availStart + perPage; i++) {
			final VehicleResource res = available.get(i);
			final int idx = i;
			addLayoutWidget(Button.builder(
							Component.literal((i == selectedAvailable ? "> " : "  ") + shortName(res.id)),
							b -> {
								selectedAvailable = idx;
								rebuild();
							})
					.bounds(layoutX0, y, LIST_W, ROW_BTN_H).build());
			y += ROW_H;
		}
		if (availPages > 1) {
			final int pages = availPages;
			addLayoutWidget(Button.builder(Component.literal("<"), b -> {
				availablePage = (availablePage + pages - 1) % pages;
				rebuild();
			}).bounds(layoutX0, layoutPagerY, 30, 16).build());
			addLayoutWidget(Button.builder(Component.literal(">"), b -> {
				availablePage = (availablePage + 1) % pages;
				rebuild();
			}).bounds(layoutX0 + LIST_W - 30, layoutPagerY, 30, 16).build());
			addLayoutWidget(Button.builder(
					Component.literal((availablePage + 1) + "/" + pages), b -> { })
					.bounds(layoutX0 + 32, layoutPagerY, LIST_W - 64, 16).build());
		}

		// ---- 右列：编组序列（每节一行 + 翻转 + 删除）----
		final int consistPages = Math.max(1, (formation.cars.size() + perPage - 1) / perPage);
		consistPage = Math.min(Math.max(0, consistPage), consistPages - 1);
		final int consistStart = consistPage * perPage;
		y = layoutListY0;
		for (int i = consistStart; i < formation.cars.size() && i < consistStart + perPage; i++) {
			final Formation.Car car = formation.cars.get(i);
			final int idx = i;
			addLayoutWidget(Button.builder(
							Component.literal(String.format("%d. %s %s", idx + 1, shortName(car.vehicleId), car.forward ? "→" : "←")),
							b -> { /* 序列行：点击无操作（信息行） */ })
					.bounds(layoutX1, y, LIST_W - 52, ROW_BTN_H).build());
			addLayoutWidget(Button.builder(Component.literal(car.forward ? "→" : "←"), b -> {
				// 头连头：方向翻转
				final Formation.Car c = formation.cars.get(idx);
				formation.cars.set(idx, new Formation.Car(c.vehicleId, !c.forward));
				MtrNext.LOGGER.info("[formation] 翻转载 {}: {} {} → {}（节数 {}）",
						idx, c.vehicleId, c.forward ? "→" : "←", c.forward ? "←" : "→", formation.cars.size());
				rebuild();
			}).bounds(layoutX1 + LIST_W - 50, y, 24, ROW_BTN_H).build());
			addLayoutWidget(Button.builder(Component.literal("X"), b -> {
				final int before = formation.cars.size();
				formation.cars.remove(idx);
				MtrNext.LOGGER.info("[formation] 删除载 {}（节数 {} → {}）", idx, before, formation.cars.size());
				rebuild();
			}).bounds(layoutX1 + LIST_W - 24, y, 24, ROW_BTN_H).build());
			y += ROW_H;
		}
		if (consistPages > 1) {
			final int pages = consistPages;
			addLayoutWidget(Button.builder(Component.literal("<"), b -> {
				consistPage = (consistPage + pages - 1) % pages;
				rebuild();
			}).bounds(layoutX1, layoutPagerY, 30, 16).build());
			addLayoutWidget(Button.builder(Component.literal(">"), b -> {
				consistPage = (consistPage + 1) % pages;
				rebuild();
			}).bounds(layoutX1 + LIST_W - 30, layoutPagerY, 30, 16).build());
			addLayoutWidget(Button.builder(
					Component.literal((consistPage + 1) + "/" + pages), b -> { })
					.bounds(layoutX1 + 32, layoutPagerY, LIST_W - 64, 16).build());
		}

		// ---- 横向按钮区（两行，互不重叠）----
		addLayoutWidget(Button.builder(Component.literal("添加 >>"), b -> addSelectedVehicle())
				.bounds(layoutX0, layoutAddY, LIST_W, 20).build());
		if (!formation.cars.isEmpty()) {
			addLayoutWidget(Button.builder(Component.literal("全部清空"), b -> {
				final int before = formation.cars.size();
				formation.cars.clear();
				consistPage = 0;
				MtrNext.LOGGER.info("[formation] 全部清空（{} → 0 节）", before);
				rebuild();
			}).bounds(layoutX1, layoutFlipY, W2, 20).build());
			addLayoutWidget(Button.builder(Component.literal("全部翻转"), b -> {
				for (int i = 0; i < formation.cars.size(); i++) {
					final Formation.Car c = formation.cars.get(i);
					formation.cars.set(i, new Formation.Car(c.vehicleId, !c.forward));
				}
				MtrNext.LOGGER.info("[formation] 全部翻转（{} 节）", formation.cars.size());
				rebuild();
			}).bounds(layoutX1 + W2 + 8, layoutFlipY, W2, 20).build());
		}

		// ---- 底部完成 ----
		addLayoutWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
				.bounds(this.width / 2 - 60, doneY, 120, 20).build());

		assertNoOverlap();
	}

	/** 登记控件（重建重叠自检表并真正加到屏幕） */
	private void addLayoutWidget(AbstractWidget w) {
		layoutWidgets.add(w);
		addRenderableWidget(w);
	}

	/**
	 * [0.60.0] 控件重叠自检：任意两个控件矩形相交都打 warn。
	 *
	 * <p>这是「布局又撞了」的<b>客观证据</b>——不靠肉眼、不靠截图。
	 * 交付要求：日志里零条 {@code 控件重叠}。
	 */
	private void assertNoOverlap() {
		int hits = 0;
		for (int i = 0; i < layoutWidgets.size(); i++) {
			for (int j = i + 1; j < layoutWidgets.size(); j++) {
				final AbstractWidget a = layoutWidgets.get(i);
				final AbstractWidget b = layoutWidgets.get(j);
				if (a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth()
						&& a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight()) {
					hits++;
					MtrNext.LOGGER.warn("[formation] 控件重叠: \"{}\"({},{},{}x{}) ∩ \"{}\"({},{},{}x{})",
							a.getMessage().getString(), a.getX(), a.getY(), a.getWidth(), a.getHeight(),
							b.getMessage().getString(), b.getX(), b.getY(), b.getWidth(), b.getHeight());
				}
			}
		}
		MtrNext.LOGGER.info("[formation] 布局重建: {}x{} maxRows={} 控件={} 重叠={}",
				this.width, this.height, layoutMaxRows, layoutWidgets.size(), hits);
	}

	/**
	 * ★ [0.57.0] 「添加 >>」的唯一实现 —— 按钮与自测共用同一条代码路径，
	 * 保证日志证明的是玩家真正走的那条路。
	 *
	 * @return 是否真的加进了一节
	 */
	boolean addSelectedVehicle() {
		if (selectedAvailable < 0 || selectedAvailable >= available.size()) {
			rejectReason = "请先在左侧列表选一辆车";
			rejectAt = System.currentTimeMillis();
			MtrNext.LOGGER.warn("[formation] add 失败：未选中（selected={} available={}）",
					selectedAvailable, available.size());
			rebuild();
			return false;
		}
		final VehicleResource res = available.get(selectedAvailable);
		final int before = formation.cars.size();
		final boolean canAdd = formation.canAddCar(before, res, true);
		MtrNext.LOGGER.info("[formation] add 请求: res={}（连接面 1:{}|2:{} 长={}）"
						+ " index={} canAddCar={} 当前节数={} 编组={}",
				res.id, res.hasGangway1 || res.hasBarrier1, res.hasGangway2 || res.hasBarrier2,
				res.length, before, canAdd, before,
				Integer.toHexString(System.identityHashCode(formation)));
		if (canAdd) {
			formation.cars.add(new Formation.Car(res.id, true));
			rejectReason = null;
			MtrNext.LOGGER.info("[formation] add 成功: {} → 节数 {}（同一实例={} store 里={}）",
					res.id, formation.cars.size(),
					FormationStore.get(sidingId) == formation,
					FormationStore.get(sidingId) == null ? -1 : FormationStore.get(sidingId).cars.size());
			// 加完自动翻到最后一页，否则玩家看不到刚加的那节
			consistPage = Math.max(0, (formation.cars.size() - 1) / Math.max(1, layoutMaxRows));
			rebuild();
			return true;
		}
		rejectReason = "无法连接：连接面不兼容，且不是同一车族";
		rejectAt = System.currentTimeMillis();
		MtrNext.LOGGER.warn("[formation] add 被 canAddCar 拒绝: res={} 节数={}", res.id, before);
		rebuild();
		return false;
	}

	// ---- 自测探针（只在 FormationSelfTest.enabled() 时被调用）----
	int selftestCars() {
		return formation.cars.size();
	}

	int selftestAvailable() {
		return available.size();
	}

	int selftestSelected() {
		return selectedAvailable;
	}

	/** [0.60.0] 供自测断言「布局没有重叠」 */
	int selftestOverlapCount() {
		int hits = 0;
		for (int i = 0; i < layoutWidgets.size(); i++) {
			for (int j = i + 1; j < layoutWidgets.size(); j++) {
				final AbstractWidget a = layoutWidgets.get(i);
				final AbstractWidget b = layoutWidgets.get(j);
				if (a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth()
						&& a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight()) {
					hits++;
				}
			}
		}
		return hits;
	}

	int selftestWidgetCount() {
		return layoutWidgets.size();
	}

	void selftestClose() {
		onClose();
	}

	@Override
	public void onClose() {
		// ★ [0.57.0] 落盘前后都留证据：文件路径、字节数、store 条数、本编组节数。
		final long sizeBefore = fileSize();
		MtrNext.LOGGER.info("[formation] onClose 前: 本编组 {} 节 store={} 条 file={}B path={}",
				formation.cars.size(), FormationStore.all().size(), sizeBefore, FormationStore.worldFile());
		FormationStore.put(sidingId, formation);
		// ★ [0.43.4] 立即落盘。此前 FormationStore.saveTo 从未被任何地方调用，
		//   编组只活在内存里，退出游戏即丢 ⇒ 真机表现为「配置过，重进世界又变空气」。
		FormationStore.saveNow();
		MtrNext.LOGGER.info("[formation] onClose 后: store={} 条 file={}B（{} → {}）",
				FormationStore.all().size(), fileSize(), sizeBefore, fileSize());
		releaseEditLock();
		// [0.4] 编组 → Siding 车辆配置桥接（baseTrainType/trainCars）
		final Object hostSiding = mtrnext.util.HostSidingHolder.consume();
		MtrNext.LOGGER.info("[formation] onClose: hostSiding={}", hostSiding == null ? "null（不会下发到 Siding）" : "有");
		if (hostSiding != null) {
			final int applied = mtrnext.formation.FormationSidingBridge.applyToSiding(hostSiding, formation);
			MtrNext.LOGGER.info("[formation] onClose: 下发结果 applied={} 节", applied);
			if (applied > 0) {
				MtrNext.LOGGER.info("Applied formation ({} cars) to YMTR siding", applied);
			}
		}
		Minecraft.getInstance().setScreen(parent);
	}

	/** [0.57.0] sidecar 文件字节数（-1 = 不存在/读不到） */
	private static long fileSize() {
		try {
			return java.nio.file.Files.size(FormationStore.worldFile());
		} catch (final Throwable t) {
			return -1L;
		}
	}

	/** [0.56.0] 界面被直接替换（非 onClose 路径）时也要解锁，否则编辑锁永远不释放 */
	@Override
	public void removed() {
		releaseEditLock();
		super.removed();
	}

	private void releaseEditLock() {
		if (editLocked) {
			editLocked = false;
			FormationStore.endEdit();
		}
	}

	private static String shortName(String id) {
		if (id == null) {
			return "?";
		}
		return id.length() > 16 ? id.substring(0, 14) + ".." : id;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		// ★ [0.52.0] 删掉手动 renderBackground：
		//   super.render(...) 内部已经会调 renderBackground，
		//   这里再调一次 = 背景被画两遍 ⇒ 真机表现为「编组界面的 GUI 重叠」。
		super.render(guiGraphics, mouseX, mouseY, delta);

		// ---- 顶部三行文字（全部在表头 54 以上，绝不压列表）----
		guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
		guiGraphics.drawCenteredString(this.font,
				Component.literal(String.format("%d 节车厢，总长 %.1f 格",
						formation.cars.size(), formation.getTotalLength())),
				this.width / 2, 28, 0xAAAAFF);

		// [0.14] 选中车详情条：编组决策辅助（几何/连接面摘要）
		// ★ [0.60.0] 从 y=62（压在列表第一行）挪到 y=40，位于副标题与表头之间。
		if (selectedAvailable >= 0 && selectedAvailable < available.size()) {
			final VehicleResource sel = available.get(selectedAvailable);
			final String face1 = sel.hasGangway1 || sel.hasBarrier1 ? "有" : "无";
			final String face2 = sel.hasGangway2 || sel.hasBarrier2 ? "有" : "无";
			final String detail = String.format("%s  长=%.1f 宽=%.1f  连接面[1:%s 2:%s]  转向架 %.0f/%.0f",
					shortName(sel.id), sel.length, sel.width, face1, face2,
					sel.bogie1Position, sel.bogie2Position);
			guiGraphics.drawCenteredString(this.font, Component.literal(detail), this.width / 2, 40, 0x88FF88);
		}

		// ---- 两列表头（y=54，列表内容从 66 起）----
		guiGraphics.drawString(this.font, Component.literal("可用车辆"), layoutX0, 54, 0xCCCCCC, false);
		guiGraphics.drawString(this.font,
				Component.literal(String.format("编组序列（%d 节）", formation.cars.size())),
				layoutX1, 54, 0xCCCCCC, false);

		// ---- 侧线容量 / 提示行（独立一行，在按钮区下方）----
		final float railLength = mtrnext.formation.FormationSidingBridge.railLengthOf(sidingId.getLeastSignificantBits());
		if (rejectReason != null && System.currentTimeMillis() - rejectAt < 3000) {
			guiGraphics.drawString(this.font, Component.literal(rejectReason), layoutX0, layoutHintY, 0xFF6666, false);
		} else if (railLength > 0 && !formation.cars.isEmpty()) {
			final double need = formation.getTotalLength();
			final boolean fits = need <= railLength + 0.05;
			guiGraphics.drawString(this.font,
					Component.literal(String.format("侧线 %.1f 格 / 本编组需 %.1f 格%s",
							railLength, need, fits ? "" : "（侧线太短，世界里只能放出前几节）")),
					layoutX0, layoutHintY, fits ? 0xAAAAAA : 0xFFAA33, false);
		}

		// ---- [0.7.1] 可用车列表的车色图标条（按当前页对齐，不再写死 8 个）----
		final int availStart = availablePage * Math.max(1, layoutMaxRows);
		int iconY = layoutListY0;
		for (int i = availStart; i < available.size() && i < availStart + layoutMaxRows; i++) {
			final VehicleResource res = available.get(i);
			final int c = res.color | 0xFF000000;
			guiGraphics.fill(layoutX0 + 2, iconY + 3, layoutX0 + 8, iconY + ROW_BTN_H - 3, c);
			guiGraphics.renderOutline(layoutX0 + 2, iconY + 3, 6, ROW_BTN_H - 6, 0xFF000000);
			iconY += ROW_H;
		}
	}

	public static void open(UUID sidingId) {
		Minecraft.getInstance().setScreen(new FormationScreen(
				Minecraft.getInstance().screen, sidingId));
		MtrNext.LOGGER.info("Formation screen opened for siding {}", sidingId);
	}
}

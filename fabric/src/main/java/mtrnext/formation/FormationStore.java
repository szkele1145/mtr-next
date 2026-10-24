package mtrnext.formation;

import mtrnext.MtrNext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编组的附加持久化（sidecar 文件，不侵入 YMTR 的 messagepack 存档格式）。
 * <p>
 * YMTR 3.x 的 Siding 序列化不含编组字段——直接扩 messagepack 会侵入
 * YMTR 数据面（违反"非分支附属"边界）。改为：
 * <ul>
 *   <li>编组按 Siding 的持久 id（UUID）为键存入
 *       {@code world/mtr-next-formations.nbt}；</li>
 *   <li>世界加载/保存钩子（后续 mixin 到 PersistentState 生命周期）同步读写；</li>
 *   <li>YMTR 存档格式零改动——卸掉 MTR Next 后世界仍正常打开
 *       （编组数据只是不再生效）。</li>
 * </ul>
 */
public final class FormationStore {

	/** sidingId(持久 UUID) → 编组 */
	private static final Map<UUID, Formation> FORMATIONS = new ConcurrentHashMap<>();

	/**
	 * ★ [0.56.0] 本世界是否已经从存档读回过（进世界后只读一次）。
	 *
	 * <p><b>为什么需要</b>：{@link FormationAutoSync} 会重试最多 30 次、每次 1 秒，
	 * 而 {@link #loadFrom} 开头就是 {@code FORMATIONS.clear()}。
	 * 若玩家在进世界后的第 5～35 秒内编辑编组，下一次重试会把内存里的编组
	 * 清掉再按<b>磁盘上还没更新的旧文件</b>重建 ⇒ 真机表现为
	 * 「刚加的编组又变回原来的」。
	 */
	private static volatile boolean loaded = false;

	/**
	 * ★ [0.56.0] 正在编辑编组的界面数量（>0 时禁止 {@link #loadNow()} 覆盖内存）。
	 *
	 * <p>{@code FormationScreen} 构造时 {@link #beginEdit()}，关闭时 {@link #endEdit()}。
	 * 编辑期间内存里的编组一定比磁盘新，任何「从磁盘重载」都必须让路。
	 */
	private static int editing = 0;

	private FormationStore() {
	}

	/** [0.56.0] 进入编组编辑界面（计数，可并发） */
	public static void beginEdit() {
		editing++;
	}

	/** [0.56.0] 离开编组编辑界面 */
	public static void endEdit() {
		if (editing > 0) {
			editing--;
		}
	}

	public static boolean isEditing() {
		return editing > 0;
	}

	/** [0.56.0] 退出世界时调用：下次进世界重新从磁盘读一次 */
	public static void resetForWorld() {
		loaded = false;
		FORMATIONS.clear();
	}

	/** [0.56.0] 只在还没读过时读一次（自动同步用；绝不覆盖已加载/编辑中的内存） */
	public static void loadOnce() {
		if (loaded || isEditing()) {
			return;
		}
		loadNow();
	}

	public static Formation getOrCreate(UUID sidingId) {
		return FORMATIONS.computeIfAbsent(sidingId, k -> new Formation());
	}

	public static Formation get(UUID sidingId) {
		return FORMATIONS.get(sidingId);
	}

	public static void put(UUID sidingId, Formation formation) {
		FORMATIONS.put(sidingId, formation);
	}

	/**
	 * [0.43.3] 全部编组快照（供进世界后自动同步到服务端）。
	 *
	 * <p>key 是 {@code new UUID(0, sidingId)}，可用
	 * {@code key.getLeastSignificantBits()} 还原 sidingId。
	 */
	public static Map<UUID, Formation> all() {
		return new HashMap<>(FORMATIONS);
	}

	/**
	 * [0.43.4] 编组 sidecar 文件路径（存档根目录下）。
	 *
	 * <p>此前 {@link #saveTo}/{@link #loadFrom} 虽然写好了，但**从未被调用**
	 * （原注释写的是「后续 mixin 到 PersistentState 生命周期」——那个「后续」一直没做），
	 * 于是编组只活在内存里，进程结束即丢。
	 * 真机日志证据：{@code 自动同步：没有已保存的编组}。
	 */
	public static Path worldFile() {
		try {
			final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
			final net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
			if (server != null) {
				return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
						.resolve("mtr-next-formations.nbt");
			}
		} catch (final Throwable ignored) {
			// 回退到全局路径
		}
		return net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath()
				.resolve("mtr-next-formations.nbt");
	}

	/** 立即把当前编组写到存档（配置变更后调用） */
	public static void saveNow() {
		try {
			final Path file = worldFile();
			saveTo(file);
			// ★ [0.57.0] 字节数 + 每条的节数：证明「写进文件的确实是玩家配的那个编组」
			final StringBuilder sb = new StringBuilder();
			for (final Map.Entry<UUID, Formation> e : FORMATIONS.entrySet()) {
				sb.append(e.getKey().getLeastSignificantBits()).append('=').append(e.getValue().cars.size()).append(' ');
			}
			MtrNext.LOGGER.info("[formation-store] 已保存到 {} （{} 条，{}B）内容: {}",
					file, FORMATIONS.size(), Files.size(file), sb.toString().trim());
		} catch (final Throwable t) {
			MtrNext.LOGGER.error("[formation-store] 保存编组失败", t);
		}
	}

	/** 从存档读回编组（显式重载；编辑中/已加载时由 loadOnce 拦住） */
	public static void loadNow() {
		if (isEditing()) {
			MtrNext.LOGGER.info("[formation-store] 正在编辑中，跳过从磁盘重载（避免覆盖内存）");
			return;
		}
		try {
			final Path file = worldFile();
			final long size = Files.exists(file) ? Files.size(file) : -1L;
			loadFrom(file);
			loaded = true;
			MtrNext.LOGGER.info("[formation-store] 从 {} 读回 {} 条（{}B）", file, FORMATIONS.size(), size);
		} catch (final Throwable t) {
			MtrNext.LOGGER.error("[formation-store] 读取编组失败", t);
		}
	}

	/** 写出 sidecar NBT（由世界保存钩子调用） */
	public static void saveTo(Path file) {
		final CompoundTag root = new CompoundTag();
		for (Map.Entry<UUID, Formation> e : FORMATIONS.entrySet()) {
			final ListTag cars = new ListTag();
			for (Formation.Car car : e.getValue().cars) {
				final CompoundTag c = new CompoundTag();
				c.putString("id", car.vehicleId);
				c.putBoolean("fwd", car.forward);
				cars.add(c);
			}
			final CompoundTag siding = new CompoundTag();
			siding.put("cars", cars);
			root.put(e.getKey().toString(), siding);
		}
		try {
			Files.createDirectories(file.getParent());
			// NBT 写盘经 CompressedStreamTools 等价物（1.20.1 NbtIo）
			net.minecraft.nbt.NbtIo.write(root, file.toFile());
		} catch (IOException ex) {
			MtrNext.LOGGER.error("Failed to save formations", ex);
		}
	}

	/** 读入 sidecar NBT（由世界加载钩子调用） */
	public static void loadFrom(Path file) {
		FORMATIONS.clear();
		if (!Files.exists(file)) {
			return;
		}
		try {
			final CompoundTag root = net.minecraft.nbt.NbtIo.read(file.toFile());
			if (root == null) {
				return;
			}
			for (String key : root.getAllKeys()) {
				try {
					final UUID sidingId = UUID.fromString(key);
					final CompoundTag siding = root.getCompound(key);
					final Formation formation = new Formation();
					final ListTag cars = siding.getList("cars", 10);
					for (int i = 0; i < cars.size(); i++) {
						final CompoundTag c = cars.getCompound(i);
						formation.cars.add(new Formation.Car(c.getString("id"), c.getBoolean("fwd")));
					}
					FORMATIONS.put(sidingId, formation);
				} catch (Exception ignore) {
					// 单个 siding 损坏不阻塞整体加载
				}
			}
			MtrNext.LOGGER.info("[formation-store] Loaded {} formations from {}", FORMATIONS.size(), file);
		} catch (IOException ex) {
			MtrNext.LOGGER.error("Failed to load formations", ex);
		}
	}
}

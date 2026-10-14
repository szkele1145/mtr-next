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

	private FormationStore() {
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
			MtrNext.LOGGER.info("Loaded {} formations", FORMATIONS.size());
		} catch (IOException ex) {
			MtrNext.LOGGER.error("Failed to load formations", ex);
		}
	}
}

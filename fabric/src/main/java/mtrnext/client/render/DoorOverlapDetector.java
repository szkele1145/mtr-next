package mtrnext.client.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [0.36.5] 门重叠离线检测（上游 {@code DynamicVehicleModel.testDoors} 的忠实移植）。
 * <p>
 * <b>上游流程</b>（{@code docs/_upstream/DynamicVehicleModel.java:119-145}）：
 * <pre>
 * for (i = 0..5) {
 *     boxes = []
 *     for (part : modelProperties.parts)
 *         part.getOpenDoorBounds(boxes, i/5)   // 只对 isDoor() 的 part
 *     boxesList.add(boxes)
 * }
 * for (i < j) {
 *     if (!boxesList[0][i].intersects(boxesList[0][j]))   // 初始不相交
 *         for (k = 1..5)
 *             if (boxesList[k][i].intersects(boxesList[k][j])) → WARN
 * }
 * </pre>
 * <p>
 * <b>门盒的构造链</b>（{@code docs/_upstream/ModelPropertiesPart.java}）：
 * <ol>
 * <li>L129-134：{@code mutableBox} ← 该 part 的所有 {@code names} 对应的<b>顶层组</b>
 *     的 {@code MutableBox}（组内所有 element 的 Box 合并）；</li>
 * <li>L158：{@code partDetailsList.add(new PartDetails(..., addBox(mutableBox.get(), x, y, z, flipped), ...))}
 *     —— 每个 <b>part 记录 × 位置实例</b> 一个 partDetails（flipped 逐实例）；</li>
 * <li>L238-257 {@code getOpenDoorBounds}：{@code partDetailsList.forEach} →
 *     每个 partDetails 生成一个 Box，<b>逐 partDetails</b> 用其 {@code flipped}，
 *     并加门位移 {@code x} / {@code z}；零厚度轴加 0.1 补偿。</li>
 * </ol>
 * <p>
 * <b>坐标系</b>：Box 为 BB 坐标系（{@code BlockbenchElement.setModelPart} 返回值），
 * 单位为格；X/Z 在 flipped 时取反（{@code addBox}，L647-651）。
 * <p>
 * <b>与旧 {@code BbModelLoader} 的关系</b>：0.36.0 起渲染已改走 MC 原生
 * {@code ModelPart} 树，旧的手算实现与渲染路径脱节；本类取代其
 * {@code detectDoorOverlaps}，并按上游语义修正了坐标���与 flipped 处理。
 */
public final class DoorOverlapDetector {

	private DoorOverlapDetector() {
	}

	/**
	 * 门盒（BB 坐标系，单位：格）。
	 * <p>
	 * 对应上游 {@code ModelPropertiesPart.PartDetails.box} + 门位移。
	 */
	public static final class DoorBox {
		public final float minX;
		public final float minY;
		public final float minZ;
		public final float maxX;
		public final float maxY;
		public final float maxZ;
		/** 该 partDetails 的翻转标记（上游 {@code partDetails.flipped}） */
		public final boolean flipped;
		/** 所属顶层组名（用于报错定位） */
		public final String groupPath;
		/** 门 X 倍率 */
		public final float doorXMult;
		/** 门 Z 倍率 */
		public final float doorZMult;
		/** 门动画型 */
		public final DoorAnimation4x doorAnim;

		DoorBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
				boolean flipped, String groupPath,
				float doorXMult, float doorZMult, DoorAnimation4x doorAnim) {
			this.minX = minX;
			this.minY = minY;
			this.minZ = minZ;
			this.maxX = maxX;
			this.maxY = maxY;
			this.maxZ = maxZ;
			this.flipped = flipped;
			this.groupPath = groupPath;
			this.doorXMult = doorXMult;
			this.doorZMult = doorZMult;
			this.doorAnim = doorAnim;
		}

		/**
		 * 相交判定（上游 {@code Box.intersects}）。
		 * <p>
		 * 上游 Box 的相交判定含 <b>0.1 的零厚度补偿</b>
		 * （{@code ModelPropertiesPart.java:244-246}）：某轴 min==max 时，
		 * 该轴的 min 减 0.1、max 加 0.1，避免「零厚度面」被判定为永不相交。
		 *
		 * @param dx 门位移 X（格）
		 * @param dz 门位移 Z（格）
		 */
		public boolean intersects(DoorBox other, double dx, double dz) {
			final float xOffset = minX == maxX ? 0.1F : 0;
			final float yOffset = minY == maxY ? 0.1F : 0;
			final float zOffset = minZ == maxZ ? 0.1F : 0;
			final float oXOffset = other.minX == other.maxX ? 0.1F : 0;
			final float oYOffset = other.minY == other.maxY ? 0.1F : 0;
			final float oZOffset = other.minZ == other.maxZ ? 0.1F : 0;
			return minX - xOffset + (float) dx <= other.maxX + oXOffset
					&& other.minX - oXOffset <= maxX + xOffset + (float) dx
					&& minY - yOffset <= other.maxY + oYOffset
					&& other.minY - oYOffset <= maxY + yOffset
					&& minZ - zOffset + (float) dz <= other.maxZ + oZOffset
					&& other.minZ - oZOffset <= maxZ + zOffset + (float) dz;
		}
	}

	/**
	 * 便捷重载：无位定义（只展开原点正位实例）。
	 *
	 * @see #doorBoxes(BbModelTree, List, Map)
	 */
	public static List<DoorBox> doorBoxes(BbModelTree tree, List<Properties4xLoader.PartInfo> parts) {
		return doorBoxes(tree, parts, null);
	}

	/**
	 * 从原生树 + 门参数构造门盒列表。
	 * <p>
	 * <b>粒度</b>（上游 L158）：每个 <b>part 记录 × 位置实例</b> 一个门盒。
	 * 由于 mtr-next 的位置实例来自 {@code positionDefinitions}，本方法接收
	 * 位定义表并按上游 {@code iteratePositions} 展开正位/翻转位。
	 * <p>
	 * <b>门盒几何</b>（上游 L129-134 + L647-651）：part 的所有 {@code names}
	 * 对应的顶层组内所有 element 的上游 Box 合并为一个 {@code mutableBox}，
	 * 再按位置实例平移（{@code x/16, y/16, z/16}）并按 flipped 对 X/Z 取反。
	 *
	 * @param tree     原生模型树（可为空）
	 * @param parts    门参数表（可为空）
	 * @param position 位定义表（可为空；为空则只展开原点正位实例）
	 * @return 门盒列表
	 */
	public static List<DoorBox> doorBoxes(BbModelTree tree,
			List<Properties4xLoader.PartInfo> parts,
			Map<String, PositionDefinitions4xLoader.PositionGroup> position) {
		final List<DoorBox> out = new ArrayList<>();
		if (tree == null || parts == null) {
			return out;
		}
		// 顶层组 → 合并包围盒（上游 nameToPart 的 MutableBox）
		final Map<String, float[]> groupBox = new HashMap<>();
		for (final BbModelTree.Element element : tree.elements) {
			if (element.groupName == null || element.groupName.isEmpty()) {
				continue;
			}
			final float[] box = element.upstreamBox();
			final float[] merged = groupBox.get(element.groupName);
			if (merged == null) {
				groupBox.put(element.groupName, new float[]{box[0], box[1], box[2], box[3], box[4], box[5]});
			} else {
				merged[0] = Math.min(merged[0], box[0]);
				merged[1] = Math.min(merged[1], box[1]);
				merged[2] = Math.min(merged[2], box[2]);
				merged[3] = Math.max(merged[3], box[3]);
				merged[4] = Math.max(merged[4], box[4]);
				merged[5] = Math.max(merged[5], box[5]);
			}
		}

		for (final Properties4xLoader.PartInfo part : parts) {
			// 上游 isDoor()：doorXMultiplier != 0 || doorZMultiplier != 0
			if (part.doorXMultiplier == 0.0F && part.doorZMultiplier == 0.0F) {
				continue;
			}
			// 合并该 part 所有 names 对应的顶层组包围盒（上游 L129-134）
			float[] merged = null;
			for (final String name : part.names) {
				final float[] b = groupBox.get(name);
				if (b == null) {
					continue;
				}
				if (merged == null) {
					merged = new float[]{b[0], b[1], b[2], b[3], b[4], b[5]};
				} else {
					merged[0] = Math.min(merged[0], b[0]);
					merged[1] = Math.min(merged[1], b[1]);
					merged[2] = Math.min(merged[2], b[2]);
					merged[3] = Math.max(merged[3], b[3]);
					merged[4] = Math.max(merged[4], b[4]);
					merged[5] = Math.max(merged[5], b[5]);
				}
			}
			if (merged == null) {
				continue;
			}
			final DoorAnimation4x anim = DoorAnimation4x.parse(part.doorAnimationType);
			// 每个位置实例一个门盒（上游 iteratePositions）
			for (final float[] instance : instances(part, position)) {
				final boolean flipped = instance[3] != 0.0F;
				final double px = instance[0] / 16.0;
				final double py = instance[1] / 16.0;
				final double pz = instance[2] / 16.0;
				// 上游 addBox（ModelPropertiesPart.java:647-651）：
				//   flipped ? -1 : 1 作用于 X 与 Z；Y 不取反
				final double sx = flipped ? -1 : 1;
				final double sz = flipped ? -1 : 1;
				out.add(new DoorBox(
						(float) (sx * merged[0] + px),
						(float) (merged[1] + py),
						(float) (sz * merged[2] + pz),
						(float) (sx * merged[3] + px),
						(float) (merged[4] + py),
						(float) (sz * merged[5] + pz),
						flipped,
						part.names.isEmpty() ? "" : part.names.get(0),
						part.doorXMultiplier, part.doorZMultiplier, anim));
			}
		}
		return out;
	}

	/**
	 * 展开 part 的位置实例（上游 {@code iteratePositions}）。
	 * <p>
	 * 上游：{@code positions} → flipped=false；{@code positionsFlipped} → flipped=true。
	 * 返回数组 [x, y, z, flipped?1:0]，单位为像素。
	 */
	private static List<float[]> instances(Properties4xLoader.PartInfo part,
			Map<String, PositionDefinitions4xLoader.PositionGroup> position) {
		final List<float[]> out = new ArrayList<>();
		if (position == null || part.positionDefinitionNames == null) {
			out.add(new float[]{0.0F, 0.0F, 0.0F, 0.0F});
			return out;
		}
		for (final String defName : part.positionDefinitionNames) {
			final PositionDefinitions4xLoader.PositionGroup group = position.get(defName);
			if (group == null) {
				continue;
			}
			for (final float[] p : group.positions) {
				out.add(new float[]{p[0], p[1], p[2], 0.0F});
			}
			for (final float[] p : group.positionsFlipped) {
				out.add(new float[]{p[0], p[1], p[2], 1.0F});
			}
		}
		if (out.isEmpty()) {
			out.add(new float[]{0.0F, 0.0F, 0.0F, 0.0F});
		}
		return out;
	}

	/**
	 * 门重叠检测（上游 {@code DynamicVehicleModel.testDoors} 的移植）。
	 * <p>
	 * 上游语义：模拟门从 t=0 到 t=1 走 5 个时间片，若两扇门在「初始不相交、
	 * 中途相交」，说明门 X/Z 倍率配错（车包作者配置错误），上游打 WARN。
	 *
	 * @return 重叠的门盒对描述列表（空 = 无问题）
	 */
	public static List<String> detectDoorOverlaps(List<DoorBox> boxes) {
		final List<String> problems = new ArrayList<>();
		if (boxes == null || boxes.size() < 2) {
			return problems;
		}
		final int slices = 5;
		for (int i = 0; i < boxes.size(); i++) {
			for (int j = i + 1; j < boxes.size(); j++) {
				final DoorBox a = boxes.get(i);
				final DoorBox b = boxes.get(j);
				// 初始（t=0）不相交才检测中途相交——上游同款语义
				if (a.intersects(b, 0, 0)) {
					continue;
				}
				for (int k = 1; k <= slices; k++) {
					final float t = (float) k / slices;
					final double ax = a.doorAnim.getDoorAnimationX(a.doorXMult, a.flipped, t) / 16.0;
					final double az = a.doorAnim.getDoorAnimationZ(a.doorZMult, a.flipped, t, true) / 16.0;
					final double bx = b.doorAnim.getDoorAnimationX(b.doorXMult, b.flipped, t) / 16.0;
					final double bz = b.doorAnim.getDoorAnimationZ(b.doorZMult, b.flipped, t, true) / 16.0;
					if (a.intersects(b, ax - bx, az - bz)) {
						problems.add("doors overlap at t=" + t + ": group=[" + a.groupPath
								+ "] vs [" + b.groupPath + "]");
						break;
					}
				}
			}
		}
		return problems;
	}
}

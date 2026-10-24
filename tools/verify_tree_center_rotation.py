"""
[0.35.4 审查] 核对 BbModelTree 的 center 推导与上游 BlockbenchElement 的 Box 公式。

上游 BlockbenchElement.java:48-58：
    vector1 = new Vector3d(x, y, z).rotateX(rx).rotateY(ry).rotateZ(rz)
    vector2 = new Vector3d(x+sx, y+sy, z+sz).rotateX(rx).rotateY(ry).rotateZ(rz)
    Box = (-(vector1.x + originX)/16, ..., -(vector2.x + originX)/16, ...)
    其中 originX = -origin[0]（已在 setModelPart L23 取负）

mtr-next 的 BbModelTree.addElement L508-510：
    centerX = (x + sizeX/2)/16      ← 未旋转
    centerY = (y + sizeY/2)/16
    centerZ = (z + sizeZ/2)/16

本脚本用真实 ERIDU 数据量化差异：
  - 有多少元素的 center 因旋转而偏移
  - 偏移幅度（格）
  - 是否会影响门重叠检测（BbModelLoader.detectDoorOverlaps 用的是
    另一套盒，与此独立；但 BbModelTree 若用于门判定则受影响）
"""
import json
import math
import os
import sys


def load_json(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def rotate(v, rx, ry, rz):
    """复刻 Vector3d.rotateX/rotateY/rotateZ 的顺序（先 X 后 Y 后 Z）"""
    x, y, z = v
    # rotateX
    c, s = math.cos(rx), math.sin(rx)
    y, z = y * c - z * s, y * s + z * c
    # rotateY
    c, s = math.cos(ry), math.sin(ry)
    x, z = x * c + z * s, -x * s + z * c
    # rotateZ
    c, s = math.cos(rz), math.sin(rz)
    x, y = x * c - y * s, x * s + y * c
    return x, y, z


def main():
    pack = sys.argv[1] if len(sys.argv) > 1 else r"F:\vibe coding工程\mtr-turbo\build\eridu-pack"
    path = os.path.join(pack, "assets", "mtr", "eridudanguei.bbmodel")
    if not os.path.exists(path):
        print("跳过：找不到", path)
        return
    root = load_json(path)

    total = 0
    rotated = 0
    max_delta = 0.0
    worst = None
    deltas = []
    for e in root.get("elements", []):
        total += 1
        origin = e.get("origin", [0, 0, 0])
        rot = e.get("rotation", [0, 0, 0])
        rx, ry, rz = (math.radians(-rot[0]), math.radians(-rot[1]), math.radians(rot[2]))
        if abs(rx) < 1e-9 and abs(ry) < 1e-9 and abs(rz) < 1e-9:
            continue
        rotated += 1
        frm, to = e["from"], e["to"]
        size = [to[i] - frm[i] for i in range(3)]
        originX = -origin[0]
        originY = -origin[1]
        originZ = origin[2]
        x = -to[0] - originX
        y = -to[1] - originY
        z = frm[2] - originZ
        # 未旋转中心（BbModelTree 的算法）
        c_unrot = ((x + size[0] / 2) / 16, (y + size[1] / 2) / 16, (z + size[2] / 2) / 16)
        # 上游：旋转后两个角点，取中点
        v1 = rotate((x, y, z), rx, ry, rz)
        v2 = rotate((x + size[0], y + size[1], z + size[2]), rx, ry, rz)
        c_rot = (
            (-(v1[0] + originX) - (-(v2[0] + originX))) / 32,
            (-(v1[1] + originY) - (-(v2[1] + originY))) / 32,
            (-(v1[2] + originZ) - (-(v2[2] + originZ))) / 32,
        )
        d = max(abs(c_unrot[i] - c_rot[i]) for i in range(3))
        deltas.append(d)
        if d > max_delta:
            max_delta = d
            worst = (e.get("name"), e.get("uuid"), rot, c_unrot, c_rot)

    print(f"元素总数: {total}")
    print(f"带非零旋转: {rotated} ({rotated*100//max(total,1)}%)")
    if deltas:
        deltas.sort()
        print(f"center 偏移（格）: 中位 {deltas[len(deltas)//2]:.4f}  "
              f"p90 {deltas[int(len(deltas)*0.9)]:.4f}  最大 {max_delta:.4f}")
        over = sum(1 for d in deltas if d > 0.01)
        print(f"偏移 > 0.01 格的元素: {over} / {len(deltas)}")
        if worst:
            name, uuid, rot, cu, cr = worst
            print(f"最大偏移元素: name={name} uuid={uuid} rotation={rot}")
            print(f"  未旋转中心 {tuple(round(v,4) for v in cu)}")
            print(f"  上游旋转后中心 {tuple(round(v,4) for v in cr)}")


if __name__ == "__main__":
    main()

package probe;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.CubeDeformation;

import java.lang.reflect.Field;
import java.util.List;

/**
 * [0.35] 实跑 MC 1.20.1 的 ModelPart$Cube，打印 6 个面的真实 UV 矩形，
 * 用来与 Blockbench 的 box-UV 布局逐面对照。
 * 面顺序由 ModelPart$Cube 构造器固定：DOWN, UP, WEST, NORTH, EAST, SOUTH。
 */
public class ProbeUv {

    private static Field fU;
    private static Field fV;

    public static void main(String[] args) throws Exception {
        int u = 100, v = 200;
        int w = 7, h = 5, d = 3;   // dx, dy, dz

        CubeListBuilder b = CubeListBuilder.create();
        b.addBox("probe", 0, 0, 0, w, h, d, CubeDeformation.NONE, u, v);
        final int texSize = 1024;
        ModelPart.Cube cube = b.getCubes().get(0).bake(texSize, texSize);

        Class<?> vertexCls = Class.forName("net.minecraft.client.model.geom.ModelPart$Vertex");
        fU = vertexCls.getDeclaredField("u");
        fU.setAccessible(true);
        fV = vertexCls.getDeclaredField("v");
        fV.setAccessible(true);

        Field polysF = ModelPart.Cube.class.getDeclaredField("polygons");
        polysF.setAccessible(true);
        Object[] polys = (Object[]) polysF.get(cube);

        Field vertsF = Class.forName("net.minecraft.client.model.geom.ModelPart$Polygon")
                .getDeclaredField("vertices");
        vertsF.setAccessible(true);

        String[] order = {"down", "up", "west", "north", "east", "south"};
        System.out.println("MC 1.20.1 real UV regions  (u=" + u + ", v=" + v
                + ", dx=" + w + ", dy=" + h + ", dz=" + d + ")");
        System.out.println("  f28=u+dz=" + (u + d) + "  f29=u+dz+dx=" + (u + d + w)
                + "  f30=u+dz+2dx=" + (u + d + 2 * w) + "  f31=u+2dz+dx=" + (u + 2 * d + w)
                + "  f32=u+2dz+2dx=" + (u + 2 * d + 2 * w)
                + "  f34=v+dz=" + (v + d) + "  f35=v+dz+dy=" + (v + d + h));

        int idx = 0;
        for (Object p : polys) {
            Object[] vs = (Object[]) vertsF.get(p);
            float[] us = new float[4];
            float[] vvs = new float[4];
            for (int i = 0; i < 4; i++) {
                us[i] = (Float) fU.get(vs[i]);
                vvs[i] = (Float) fV.get(vs[i]);
            }
            System.out.printf("  %-6s u:[%d..%d]  v:[%d..%d]%n", order[idx++],
                    (int) (min(us) * texSize), (int) (max(us) * texSize),
                    (int) (min(vvs) * texSize), (int) (max(vvs) * texSize));
        }
    }

    private static float min(float[] a) {
        float m = a[0];
        for (float x : a) {
            if (x < m) {
                m = x;
            }
        }
        return m;
    }

    private static float max(float[] a) {
        float m = a[0];
        for (float x : a) {
            if (x > m) {
                m = x;
            }
        }
        return m;
    }
}

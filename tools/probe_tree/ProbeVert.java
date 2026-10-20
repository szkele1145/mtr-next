package probe;
import net.minecraft.client.model.geom.*;
import net.minecraft.client.model.geom.builders.*;
import java.lang.reflect.*;
import java.util.*;
public class ProbeVert {
  static Field CF, CUBEPOLY, PF, UF, VF, CH, XF, YF, ZF;
  public static void main(String[] a) throws Exception {
    CF = ModelPart.class.getDeclaredField("cubes"); CF.setAccessible(true);
    Class<?> cubeCls = Class.forName("net.minecraft.client.model.geom.ModelPart$Cube");
    CUBEPOLY = cubeCls.getDeclaredField("polygons"); CUBEPOLY.setAccessible(true);
    Class<?> poly = Class.forName("net.minecraft.client.model.geom.ModelPart$Polygon");
    PF = poly.getDeclaredField("vertices"); PF.setAccessible(true);
    Class<?> vtx = Class.forName("net.minecraft.client.model.geom.ModelPart$Vertex");
    UF = vtx.getDeclaredField("u"); UF.setAccessible(true);
    VF = vtx.getDeclaredField("v"); VF.setAccessible(true);
    XF = vtx.getDeclaredField("x"); XF.setAccessible(true);
    YF = vtx.getDeclaredField("y"); YF.setAccessible(true);
    ZF = vtx.getDeclaredField("z"); ZF.setAccessible(true);
    CH = ModelPart.class.getDeclaredField("children"); CH.setAccessible(true);
    MeshDefinition mesh = new MeshDefinition();
    PartDefinition root = mesh.getRoot();
    PartDefinition anchor = root.addOrReplaceChild("anchor", CubeListBuilder.create(), PartPose.ZERO);
    PartDefinition holder = anchor.addOrReplaceChild("holder", CubeListBuilder.create(), PartPose.offsetAndRotation(0, -16, 0, 0, 0, 0));
    PartDefinition cubeNode = holder.addOrReplaceChild("cube", CubeListBuilder.create().addBox("cube", -16,-16,0, 16,16,16, new CubeDeformation(0), 100, 200), PartPose.ZERO);
    ModelPart baked = LayerDefinition.create(mesh, 1024, 1024).bakeRoot();
    // 鎵?cube 鑺傜偣锛屾墦鍗板叾椤剁偣灞€閮ㄥ潗鏍?    ModelPart cubePart = baked.getChild("anchor").getChild("holder").getChild("cube");
    List<?> cubes = (List<?>) CF.get(cubePart);
    for (Object c : cubes) {
      Object[] polys = (Object[]) CUBEPOLY.get(c);
      for (Object po : polys) {
        Object[] vertices = (Object[]) PF.get(po);
        StringBuilder sb = new StringBuilder("poly:");
        for (Object vertex : vertices) sb.append(" (").append(XF.getFloat(vertex)).append(",").append(YF.getFloat(vertex)).append(",").append(ZF.getFloat(vertex)).append(")");
        System.out.println(sb);
      }
    }
  }
}
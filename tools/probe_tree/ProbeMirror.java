package probe;
import net.minecraft.client.model.geom.*;
import net.minecraft.client.model.geom.builders.*;
import java.lang.reflect.*;
import java.util.*;
public class ProbeMirror {
  static Field CF, CUBEPOLY, PF, UF, VF;
  public static void main(String[] a) throws Exception {
    CF = ModelPart.class.getDeclaredField("cubes"); CF.setAccessible(true);
    Class<?> cubeCls = Class.forName("net.minecraft.client.model.geom.ModelPart$Cube");
    CUBEPOLY = cubeCls.getDeclaredField("polygons"); CUBEPOLY.setAccessible(true);
    Class<?> poly = Class.forName("net.minecraft.client.model.geom.ModelPart$Polygon");
    PF = poly.getDeclaredField("vertices"); PF.setAccessible(true);
    Class<?> vtx = Class.forName("net.minecraft.client.model.geom.ModelPart$Vertex");
    UF = vtx.getDeclaredField("u"); UF.setAccessible(true);
    VF = vtx.getDeclaredField("v"); VF.setAccessible(true);
    for (boolean mir : new boolean[]{false, true}) {
      MeshDefinition mesh = new MeshDefinition();
      PartDefinition root = mesh.getRoot();
      CubeListBuilder b = CubeListBuilder.create();
      if (mir) b.mirror(true);
      b.addBox("cube", -16,-16,0, 16,16,16, new CubeDeformation(0), 100, 200);
      root.addOrReplaceChild("cube", b, PartPose.ZERO);
      ModelPart baked = LayerDefinition.create(mesh, 1024, 1024).bakeRoot();
      System.out.println("mirror=" + mir);
      List<?> cubes = (List<?>) CF.get(baked.getChild("cube"));
      Object[] polys = (Object[]) CUBEPOLY.get(cubes.get(0));
      for (int i=0;i<polys.length;i++) {
        Object[] verts = (Object[]) PF.get(polys[i]);
        StringBuilder sb = new StringBuilder("  poly"+i+":");
        for (Object v : verts) sb.append(" u=").append((int)(UF.getFloat(v)*1024)).append(" v=").append((int)(VF.getFloat(v)*1024));
        System.out.println(sb);
      }
    }
  }
}
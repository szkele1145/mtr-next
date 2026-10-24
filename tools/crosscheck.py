#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""逐字实现上游两套公式并交叉比对。

A) 上游 AABB 公式（BlockbenchElement.setModelPart 尾部，逐字）：
   originX = -ox ; originY = -oy - 16*off ; originZ = +oz
   x = -to.x - originX ; y = -to.y - originY - 16*off ; z = from.z - originZ
   v1 = (x,y,z).rotateX(rx).rotateY(ry).rotateZ(rz)
   Box( -(v1.x+originX)/16, -(v1.y+originY)/16, -(v1.z+originZ)/16, ... )

B) 上游 ModelPart 路径（GroupTransformations + MC ModelPart 语义）：
   组链 q_i = p_i - combined ; combined += p_i ; 首级 y 再减 offset
   元素级作为链的最后一级
   MC ModelPart.render: translate(p/16) -> Rz -> Ry -> Rx
   最终再乘宿主 Ry(PI)*Rx(PI)

比较 A 与 B 的世界包围盒。
"""
import json, sys, math

def mul(a,b):
    n=4
    return [[sum(a[i][k]*b[k][j] for k in range(n)) for j in range(n)] for i in range(n)]
def rot_x(a):
    c,s=math.cos(a),math.sin(a); return [[1,0,0,0],[0,c,-s,0],[0,s,c,0],[0,0,0,1]]
def rot_y(a):
    c,s=math.cos(a),math.sin(a); return [[c,0,s,0],[0,1,0,0],[-s,0,c,0],[0,0,0,1]]
def rot_z(a):
    c,s=math.cos(a),math.sin(a); return [[c,-s,0,0],[s,c,0,0],[0,0,1,0],[0,0,0,1]]
def trans(x,y,z): return [[1,0,0,x],[0,1,0,y],[0,0,1,z],[0,0,0,1]]
IDENT=[[1,0,0,0],[0,1,0,0],[0,0,1,0],[0,0,0,1]]
def ap(m,v):
    x,y,z=v
    return (m[0][0]*x+m[0][1]*y+m[0][2]*z+m[0][3],
            m[1][0]*x+m[1][1]*y+m[1][2]*z+m[1][3],
            m[2][0]*x+m[2][1]*y+m[2][2]*z+m[2][3])

def upstream_aabb(e, off):
    ox,oy,oz = e.get('origin',[0,0,0])
    rot = e.get('rotation',[0,0,0]) or [0,0,0]
    fr,to = e['from'], e['to']
    originX = -ox; originY = -oy - off*16; originZ = oz
    rx = math.radians(-rot[0]); ry = math.radians(-rot[1]); rz = math.radians(rot[2])
    x = -to[0] - originX; y = -to[1] - originY - off*16; z = fr[2] - originZ
    sx = int(round(to[0]-fr[0])); sy = int(round(to[1]-fr[1])); sz = int(round(to[2]-fr[2]))
    m = mul(rot_z(rz), mul(rot_y(ry), rot_x(rx)))
    v1 = ap(m,(x,y,z)); v2 = ap(m,(x+sx,y+sy,z+sz))
    return (-(v1[0]+originX)/16, -(v1[1]+originY)/16, -(v1[2]+originZ)/16,
            -(v2[0]+originX)/16, -(v2[1]+originY)/16, -(v2[2]+originZ)/16)

def upstream_modelpart(e, off, chain):
    """chain: [(origin, rotation, is_root)] 顶层组 -> 元素级"""
    m = IDENT; combined=[0.0,0.0,0.0]; offset = off*16.0
    ox,oy,oz = e.get('origin',[0,0,0]); erot = e.get('rotation',[0,0,0]) or [0,0,0]
    full = list(chain) + [((ox,oy,oz), erot, False)]
    for (o,r,is_root) in full:
        p=(-o[0],-o[1],o[2])
        q=(p[0]-combined[0], p[1]-combined[1]-offset, p[2]-combined[2])
        combined=[combined[0]+p[0],combined[1]+p[1],combined[2]+p[2]]
        if is_root: rx=ry=rz=0.0
        else:
            rx=math.radians(-r[0]); ry=math.radians(-r[1]); rz=math.radians(r[2])
        m = mul(m, mul(trans(*q), mul(rot_z(rz), mul(rot_y(ry), rot_x(rx)))))
        offset=0.0
    return m

def main():
    path=sys.argv[1]; off=float(sys.argv[2]) if len(sys.argv)>2 else 1.0
    root=json.load(open(path,encoding='utf-8'))
    elements={e['uuid']:e for e in root['elements']}
    a_pts=[]; b_pts=[]
    for grp in root['outliner']:
        if not isinstance(grp,dict): continue
        go=grp.get('origin',[0,0,0]); gr=grp.get('rotation',[0,0,0]) or [0,0,0]
        for kid in (grp.get('children') or []):
            if not isinstance(kid,str): continue
            e=elements.get(kid)
            if e is None: continue
            # A) 上游 AABB（无组链）
            a_pts.append(upstream_aabb(e,off))
            # B) ModelPart 路径 + 宿主 Ry(PI)Rx(PI)
            mm = upstream_modelpart(e,off,[(go,gr,True)])
            wm = mul(rot_y(math.pi), mul(rot_x(math.pi), mm))
            ox,oy,oz = e.get('origin',[0,0,0])
            fr,to=e['from'],e['to']
            lx=-to[0]+ox; ly=-to[1]+oy; lz=fr[2]-oz
            sx=int(round(to[0]-fr[0])); sy=int(round(to[1]-fr[1])); sz=int(round(to[2]-fr[2]))
            for i in (0,1):
                for j in (0,1):
                    for k in (0,1):
                        b_pts.append(tuple(v/16.0 for v in ap(wm,(lx+i*sx, ly+j*sy, lz+k*sz))))
    def bbox(pts):
        xs=[p[0] for p in pts]; ys=[p[1] for p in pts]; zs=[p[2] for p in pts]
        return (min(xs),max(xs),min(ys),max(ys),min(zs),max(zs))
    A=bbox(a_pts); B=bbox(b_pts)
    print("offset=%.1f" % off)
    print("  A) upstream AABB formula : X[%.3f,%.3f] Y[%.3f,%.3f] Z[%.3f,%.3f]" % A)
    print("  B) ModelPart + host R*   : X[%.3f,%.3f] Y[%.3f,%.3f] Z[%.3f,%.3f]" % B)
    # 逐元素比较中心
    d=[]
    for i,(p,q) in enumerate(zip(a_pts,b_pts)):
        if i>=len(b_pts): break
        pc=((p[0]+p[3])/2,(p[1]+p[4])/2,(p[2]+p[5])/2)
        qc=((q[0]+q[3])/2,(q[1]+q[4])/2,(q[2]+q[5])/2)
        d.append((abs(pc[0]-qc[0]),abs(pc[1]-qc[1]),abs(pc[2]-qc[2])))
    if d:
        print("  center diff max: X %.4f Y %.4f Z %.4f" % (max(x[0] for x in d),max(x[1] for x in d),max(x[2] for x in d)))
main()

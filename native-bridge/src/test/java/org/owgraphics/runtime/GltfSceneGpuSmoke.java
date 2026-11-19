package org.owgraphics.runtime;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33.*;

/**
 * True Mesa framebuffer proof of GLB parent-child world transforms, two meshes,
 * three material primitives and all three independently visible colors.
 */
public final class GltfSceneGpuSmoke {
    private static int op(GraphicsRuntimeExtension gpu,String name,Object... args) {
        return (int)gpu.invoke(name,List.of(args));
    }
    private static int[] pixel(int x,int y) {
        ByteBuffer data=BufferUtils.createByteBuffer(4);
        glReadBuffer(GL_BACK);
        glReadPixels(x,y,1,1,GL_RGBA,GL_UNSIGNED_BYTE,data);
        return new int[]{data.get(0)&255,data.get(1)&255,data.get(2)&255};
    }
    private static void check(boolean c,String reason) {
        if(!c)throw new AssertionError(reason);
    }
    private static Path projectRoot() {
        Path cwd=Path.of("").toAbsolutePath();
        for(Path dir:List.of(cwd,cwd.resolve("ow-graphics"),cwd.getParent(),
                cwd.resolve("..").resolve("ow-graphics"))) {
            if(dir!=null && Files.exists(dir.resolve("examples/gltf_scene/assets/multi_scene.glb")))
                return dir;
        }
        throw new AssertionError("GLB fixture not found");
    }
    private static List<Double> multiply(List<?> a,List<?> b) {
        List<Double> result=new ArrayList<>(16);
        for(int i=0;i<16;i++)result.add(0.0);
        for(int col=0;col<4;col++)
            for(int row=0;row<4;row++) {
                double value=0;
                for(int k=0;k<4;k++)
                    value+=((Number)a.get(k*4+row)).doubleValue()
                            *((Number)b.get(col*4+k)).doubleValue();
                result.set(col*4+row,value);
            }
        return result;
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        var asset=GltfSceneParser.loadRelative(
                "examples/gltf_scene/assets/multi_scene.glb",projectRoot());
        var primitives=(List<List<Object>>)asset.get(0);
        var parents=(List<Integer>)asset.get(1);
        var local=(List<List<Double>>)asset.get(2);
        var refs=(List<List<Integer>>)asset.get(3);
        check(primitives.size()==3 && parents.size()==4,"GLB scene fixture structure");

        List<List<Double>> world=new ArrayList<>();
        for(int i=0;i<parents.size();i++)
            world.add(parents.get(i)==-1?local.get(i):multiply(world.get(parents.get(i)),local.get(i)));
        check(Math.abs(world.get(1).get(12)-0.65)<0.001,"right child world X");
        check(Math.abs(world.get(2).get(12)+0.65)<0.001,"left child world X");

        try(var gpu=new GraphicsRuntimeExtension()){
            op(gpu,"graphics.window.open",320,240,"OW GLB scene framebuffer","opengl");
            int shader=op(gpu,"graphics.shader.create",
                    "#version 330 core\nlayout(location=0) in vec3 position;\nuniform mat4 uModel;\nvoid main(){gl_Position=uModel*vec4(position,1.0);}",
                    "#version 330 core\nout vec4 pixel;\nuniform vec4 uTint;\nvoid main(){pixel=uTint;}");
            List<Integer> handles=new ArrayList<>();
            for(var primitive:primitives)
                handles.add(op(gpu,"graphics.mesh.create_lit_indexed",
                        primitive.get(0),primitive.get(1),primitive.get(2),primitive.get(3)));
            op(gpu,"graphics.opengl.clear",0,0,0);
            op(gpu,"graphics.shader.bind",shader);
            int draws=0;
            for(int node=0;node<world.size();node++) {
                for(int id:refs.get(node)) {
                    op(gpu,"graphics.shader.uniform_mat4",shader,"uModel",world.get(node));
                    var tint=(List<?>)primitives.get(id).get(4);
                    op(gpu,"graphics.shader.uniform4",shader,"uTint",
                            tint.get(0),tint.get(1),tint.get(2),tint.get(3));
                    op(gpu,"graphics.mesh.draw",handles.get(id));
                    draws++;
                }
            }
            check(draws==4,"two left primitives and two distinct blue nodes");
            glFinish();
            int[] copper=pixel(56,105);
            int[] green=pixel(56,157);
            int[] right=pixel(264,120);
            int[] bottom=pixel(160,18);
            int[] empty=pixel(160,215);
            check(copper[0]>160 && copper[1]<150 && copper[2]<110,
                    "base copper panel: "+java.util.Arrays.toString(copper));
            check(green[1]>170 && green[0]<130,
                    "second primitive green overlay: "+java.util.Arrays.toString(green));
            check(right[2]>170 && right[0]<140,
                    "blue right-side mesh: "+java.util.Arrays.toString(right));
            check(bottom[2]>170 && bottom[0]<140,
                    "second root blue mesh: "+java.util.Arrays.toString(bottom));
            check(empty[0]<20 && empty[1]<20 && empty[2]<20,
                    "empty background not cleared");
            for(int handle:handles)op(gpu,"graphics.mesh.destroy",handle);
            op(gpu,"graphics.shader.destroy",shader);
            check(glGetError()==GL_NO_ERROR,"OpenGL must have no error");
        }
        System.out.println("PASS: GLB multi-mesh, parent-child transforms, 3 primitive colors and GPU cleanup");
    }
}

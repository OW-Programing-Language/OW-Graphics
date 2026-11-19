package org.owgraphics.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Explicit glTF 2.0 animation + linear-blend-skinning subset. The model and its
 * animation are plain OW arrays, not JVM handles with unspecified lifetimes.
 * Native CPU parsing/sampling never needs a GLFW window or an OpenGL context.
 *
 * One skin, one skinned mesh with one TRIANGLES primitive, <=32 joints,
 * <=64 scene nodes, <=32 animation channels and <=256 keyframes/channel.
 * LINEAR and STEP, translation/rotation/scale (rotation uses quaternion SLERP).
 * JOINTS_0 supports UBYTE/USHORT and WEIGHTS_0 FLOAT VEC4.
 *
 * Rig fields:
 * [positions,uvs,normals,indices,jointIds,weights,parents,translations,
 *  rotations,scales,skinJointNodeIds,inverseBindMatrices,animationChannels,
 *  meshNodeId]
 */
final class GltfSkinParser {
    private static final int MAX_NODES = 64;
    private static final int MAX_JOINTS = 32;
    private static final int MAX_CHANNELS = 32;
    private static final int MAX_KEYS = 256;
    private GltfSkinParser() {}

    private static JsonObject obj(JsonElement element,String label) {
        if(element==null || !element.isJsonObject())
            throw new IllegalArgumentException(label+" must be an object");
        return element.getAsJsonObject();
    }
    private static JsonArray arr(JsonObject owner,String name) {
        if(owner==null || !owner.has(name) || !owner.get(name).isJsonArray())
            throw new IllegalArgumentException(name+" must be an array");
        return owner.getAsJsonArray(name);
    }
    private static int integer(JsonElement value,String label) {
        if(value==null || !value.isJsonPrimitive())
            throw new IllegalArgumentException(label+" must be integer");
        try {
            double n=value.getAsDouble();
            if(!Double.isFinite(n) || n!=Math.rint(n) || n<Integer.MIN_VALUE || n>Integer.MAX_VALUE)
                throw new IllegalArgumentException(label+" must be integer");
            return (int)n;
        } catch(NumberFormatException | UnsupportedOperationException invalid) {
            throw new IllegalArgumentException(label+" must be integer",invalid);
        }
    }
    private static int integer(JsonObject owner,String name) {
        return integer(owner.get(name),name);
    }
    private static double finite(double value,String label) {
        if(!Double.isFinite(value) || Math.abs(value)>Float.MAX_VALUE)
            throw new IllegalArgumentException(label+" must be finite float");
        return value;
    }
    private static List<Double> values(JsonObject owner,String key,int length,double... defaults) {
        if(!owner.has(key)) {
            List<Double> result=new ArrayList<>();
            for(double value:defaults)result.add(value);
            return result;
        }
        JsonArray input=arr(owner,key);
        if(input.size()!=length)throw new IllegalArgumentException(key+" length must be "+length);
        List<Double> result=new ArrayList<>(length);
        for(JsonElement entry:input) {
            if(!entry.isJsonPrimitive())
                throw new IllegalArgumentException(key+" must contain numeric components");
            double value;
            try {value=entry.getAsDouble();}
            catch(RuntimeException invalid) {throw new IllegalArgumentException(key+" must be numeric",invalid);}
            result.add(finite(value,key));
        }
        return result;
    }

    static List<Object> loadRelative(String filename,Path root) {
        if(filename==null || filename.isBlank() || filename.length()>4096)
            throw new IllegalArgumentException("Skinned glTF file path required");
        String lower=filename.toLowerCase(Locale.ROOT);
        if(!lower.endsWith(".gltf") && !lower.endsWith(".glb"))
            throw new IllegalArgumentException("Skinned model must be .gltf or .glb");
        Path requested;
        try {requested=Path.of(filename);}
        catch(RuntimeException invalid) {throw new IllegalArgumentException("Invalid glTF path",invalid);}
        if(requested.isAbsolute())throw new IllegalArgumentException("Absolute glTF path rejected");
        for(Path part:requested)if(part.toString().equals(".."))
            throw new IllegalArgumentException("Parent traversal rejected");
        try {
            Path realRoot=root.toRealPath();
            Path file=realRoot.resolve(requested).toRealPath();
            if(!file.startsWith(realRoot) || !Files.isRegularFile(file))
                throw new IllegalArgumentException("Skinned model must be within project directory");
            long bytes=Files.size(file);
            if(bytes<1 || bytes>(lower.endsWith(".glb")?5_300_000:1_048_576))
                throw new IllegalArgumentException("glTF animation asset too large");
            return lower.endsWith(".glb")?fromDocument(GltfSceneParser.readGlb(Files.readAllBytes(file))):
                    parseJson(Files.readString(file,java.nio.charset.StandardCharsets.UTF_8));
        } catch(IOException e) {
            throw new IllegalArgumentException("Cannot read glTF animation asset",e);
        }
    }
    static List<Object> parseJson(String json) {
        return fromDocument(GltfModelParser.document(json));
    }

    static List<Object> fromDocument(GltfModelParser.EmbeddedDocument doc) {
        JsonObject root=doc.root();
        if(root.has("extensionsRequired") && !arr(root,"extensionsRequired").isEmpty())
            throw new IllegalArgumentException("Required glTF extensions unsupported");
        JsonArray meshes=arr(root,"meshes");
        if(meshes.size()!=1)throw new IllegalArgumentException("One skinned mesh supported");
        JsonArray primitives=arr(obj(meshes.get(0),"mesh"),"primitives");
        if(primitives.size()!=1)throw new IllegalArgumentException("One skinned TRIANGLES primitive supported");
        JsonObject primitive=obj(primitives.get(0),"primitive");
        List<Object> geometry=GltfModelParser.parsePrimitive(root,doc.data(),primitive);
        JsonObject attributes=obj(primitive.get("attributes"),"attributes");
        GltfModelParser.Accessor ids=GltfModelParser.accessor(root,doc.data(),integer(attributes,"JOINTS_0"));
        GltfModelParser.Accessor weights=GltfModelParser.accessor(root,doc.data(),integer(attributes,"WEIGHTS_0"));
        int vertices=((List<?>)geometry.get(0)).size()/3;
        if(ids.components()!=4 || ids.count()!=vertices
                || ids.componentType()!=5121 && ids.componentType()!=5123)
            throw new IllegalArgumentException("JOINTS_0 requires unsigned VEC4 per vertex");
        if(weights.components()!=4 || weights.count()!=vertices || weights.componentType()!=5126)
            throw new IllegalArgumentException("WEIGHTS_0 requires FLOAT VEC4 per vertex");
        if(attributes.has("JOINTS_1") || attributes.has("WEIGHTS_1")
                || primitive.has("targets"))
            throw new IllegalArgumentException("More than 4 joint influences and morphs unsupported");

        JsonArray skinArr=arr(root,"skins");
        if(skinArr.size()!=1)throw new IllegalArgumentException("One glTF skin supported");
        JsonObject skin=obj(skinArr.get(0),"skin");
        JsonArray jointArray=arr(skin,"joints");
        if(jointArray.isEmpty() || jointArray.size()>MAX_JOINTS)
            throw new IllegalArgumentException("Skin joint count must be 1..32");
        JsonArray nodes=arr(root,"nodes");
        if(nodes.isEmpty() || nodes.size()>MAX_NODES)
            throw new IllegalArgumentException("Node count must be 1..64");
        List<Integer> skinJoints=new ArrayList<>();
        Set<Integer> distinct=new HashSet<>();
        for(JsonElement item:jointArray) {
            int node=integer(item,"skin joint");
            if(node<0 || node>=nodes.size() || !distinct.add(node))
                throw new IllegalArgumentException("Invalid/repeated skin joint node");
            skinJoints.add(node);
        }
        List<Double> jointOut=new ArrayList<>(vertices*4);
        List<Double> weightOut=new ArrayList<>(vertices*4);
        for(int i=0;i<vertices;i++) {
            double total=0;
            for(int c=0;c<4;c++) {
                double id=ids.value(i,c);
                double weight=weights.value(i,c);
                if(id<0 || id>=skinJoints.size() || weight<0 || weight>1)
                    throw new IllegalArgumentException("Joint index/weight out of range");
                jointOut.add(id);
                weightOut.add(weight);
                total+=weight;
            }
            if(total<1e-9 || Math.abs(total-1.0)>0.001)
                throw new IllegalArgumentException("Skin weights must sum to one");
            if(total!=1.0) for(int c=0;c<4;c++)
                weightOut.set(i*4+c,weightOut.get(i*4+c)/total);
        }

        List<Object> inverseBinds=new ArrayList<>();
        if(skin.has("inverseBindMatrices")) {
            GltfModelParser.Accessor a=GltfModelParser.accessor(root,doc.data(),
                    integer(skin,"inverseBindMatrices"));
            if(a.components()!=16 || a.count()!=skinJoints.size() || a.componentType()!=5126)
                throw new IllegalArgumentException("inverseBindMatrices must be FLOAT MAT4 per joint");
            for(int j=0;j<a.count();j++) {
                List<Double> matrix=new ArrayList<>(16);
                for(int c=0;c<16;c++)matrix.add(a.value(j,c));
                inverseBinds.add(matrix);
            }
        } else {
            for(int j=0;j<skinJoints.size();j++)inverseBinds.add(list(identity()));
        }

        List<Integer> parents=new ArrayList<>();
        List<Object> translations=new ArrayList<>();
        List<Object> rotations=new ArrayList<>();
        List<Object> scales=new ArrayList<>();
        for(int i=0;i<nodes.size();i++)parents.add(-1);
        int meshNode=-1;
        for(int i=0;i<nodes.size();i++) {
            JsonObject node=obj(nodes.get(i),"node");
            if(node.has("matrix") || node.has("extensions") || node.has("weights"))
                throw new IllegalArgumentException("Animated rig requires TRS nodes, no morphs");
            if(node.has("mesh")) {
                if(integer(node,"mesh")!=0 || !node.has("skin") || integer(node,"skin")!=0
                        || meshNode>=0)
                    throw new IllegalArgumentException("Exactly one mesh node must reference skin zero");
                meshNode=i;
            }
            translations.add(values(node,"translation",3,0,0,0));
            List<Double> quat=values(node,"rotation",4,0,0,0,1);
            rotations.add(list(normalizeQuaternion(quat)));
            scales.add(values(node,"scale",3,1,1,1));
            if(node.has("children")) {
                for(JsonElement child:arr(node,"children")) {
                    int id=integer(child,"child");
                    if(id<0 || id>=nodes.size() || id==i || parents.get(id)!=-1)
                        throw new IllegalArgumentException("Joint graph child invalid/shared");
                    parents.set(id,i);
                }
            }
        }
        if(meshNode<0)throw new IllegalArgumentException("No skinned mesh node");
        // Validate full graph including unreachable nodes, not just a chosen scene.
        for(int i=0;i<parents.size();i++)validateParents(i,parents,new byte[parents.size()]);

        JsonArray animations=arr(root,"animations");
        if(animations.size()!=1)throw new IllegalArgumentException("Exactly one glTF animation clip supported");
        JsonObject animation=obj(animations.get(0),"animation");
        JsonArray samplers=arr(animation,"samplers");
        JsonArray channels=arr(animation,"channels");
        if(channels.isEmpty() || channels.size()>MAX_CHANNELS)
            throw new IllegalArgumentException("Animation needs 1..32 channels");
        List<Object> clip=new ArrayList<>();
        Set<String> channelsUsed=new HashSet<>();
        for(JsonElement channelElement:channels) {
            JsonObject channel=obj(channelElement,"animation channel");
            int samplerIndex=integer(channel,"sampler");
            if(samplerIndex<0 || samplerIndex>=samplers.size())
                throw new IllegalArgumentException("Animation sampler out of range");
            JsonObject sampler=obj(samplers.get(samplerIndex),"animation sampler");
            JsonObject target=obj(channel.get("target"),"animation target");
            int node=integer(target,"node");
            if(node<0 || node>=nodes.size())
                throw new IllegalArgumentException("Animation target node out of range");
            String path=target.get("path").getAsString();
            if(!path.equals("translation") && !path.equals("rotation") && !path.equals("scale"))
                throw new IllegalArgumentException("Only TRS animation paths supported");
            if(!channelsUsed.add(node+":"+path))
                throw new IllegalArgumentException("Duplicate animated node/path");
            String interpolation=sampler.has("interpolation")?
                    sampler.get("interpolation").getAsString():"LINEAR";
            if(!interpolation.equals("LINEAR") && !interpolation.equals("STEP"))
                throw new IllegalArgumentException("Only LINEAR/STEP glTF samplers supported");
            GltfModelParser.Accessor in=GltfModelParser.accessor(root,doc.data(),integer(sampler,"input"));
            GltfModelParser.Accessor out=GltfModelParser.accessor(root,doc.data(),integer(sampler,"output"));
            int components=path.equals("rotation")?4:3;
            if(in.components()!=1 || in.componentType()!=5126 || out.components()!=components
                    || out.componentType()!=5126 || in.count()!=out.count() || in.count()>MAX_KEYS)
                throw new IllegalArgumentException("Animation samples require matching FLOAT time and VEC3/VEC4");
            List<Double> times=new ArrayList<>();
            List<Object> keyValues=new ArrayList<>();
            for(int k=0;k<in.count();k++) {
                double t=in.value(k,0);
                if(t<0 || k>0 && t<=times.get(k-1))
                    throw new IllegalArgumentException("Animation keyframe time must strictly increase");
                times.add(t);
                List<Double> keys=new ArrayList<>();
                for(int c=0;c<components;c++)keys.add(out.value(k,c));
                keyValues.add(path.equals("rotation")?list(normalizeQuaternion(keys)):keys);
            }
            clip.add(List.of(node,path,interpolation,times,keyValues));
        }
        List<Object> result=new ArrayList<>(14);
        result.add(geometry.get(0));result.add(geometry.get(1));
        result.add(geometry.get(2));result.add(geometry.get(3));
        result.add(jointOut);result.add(weightOut);
        result.add(parents);result.add(translations);result.add(rotations);result.add(scales);
        result.add(skinJoints);result.add(inverseBinds);result.add(clip);result.add(meshNode);
        return result;
    }

    private static void validateParents(int node,List<Integer> parents,byte[] state) {
        if(state[node]==1)throw new IllegalArgumentException("Cycle in rig nodes");
        if(state[node]==2)return;
        state[node]=1;
        int parent=parents.get(node);
        if(parent>=0)validateParents(parent,parents,state);
        state[node]=2;
    }

    private static List<Double> list(double[] values) {
        List<Double> result=new ArrayList<>(values.length);
        for(double v:values)result.add(v);
        return result;
    }
    private static double[] identity() {
        return new double[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
    }
    private static double[] normalizeQuaternion(List<Double> q) {
        double len=0;
        for(double v:q)len+=v*v;
        len=Math.sqrt(len);
        if(!Double.isFinite(len) || len<1e-9)
            throw new IllegalArgumentException("Quaternion must be nonzero");
        double[] result=new double[q.size()];
        for(int i=0;i<q.size();i++)result[i]=q.get(i)/len;
        return result;
    }

    private static double[] matrix(List<?> translation,List<?> rotation,List<?> scale) {
        double x=num(rotation.get(0)),y=num(rotation.get(1)),
                z=num(rotation.get(2)),w=num(rotation.get(3));
        double[] q=normalizeQuaternion(List.of(x,y,z,w));
        x=q[0];y=q[1];z=q[2];w=q[3];
        double sx=num(scale.get(0)),sy=num(scale.get(1)),sz=num(scale.get(2));
        double[] result={
            (1-2*(y*y+z*z))*sx,2*(x*y+z*w)*sx,2*(x*z-y*w)*sx,0,
            2*(x*y-z*w)*sy,(1-2*(x*x+z*z))*sy,2*(y*z+x*w)*sy,0,
            2*(x*z+y*w)*sz,2*(y*z-x*w)*sz,(1-2*(x*x+y*y))*sz,0,
            num(translation.get(0)),num(translation.get(1)),num(translation.get(2)),1
        };
        for(double v:result)finite(v,"node transform");
        return result;
    }
    private static double num(Object value) {
        if(!(value instanceof Number n))throw new IllegalArgumentException("Expected numeric value");
        return finite(n.doubleValue(),"number");
    }
    private static List<?> requiredList(Object value,String label,int maximum) {
        if(!(value instanceof List<?> values) || values.size()>maximum)
            throw new IllegalArgumentException(label+" must be bounded array");
        return values;
    }

    private static double[] multiply(double[] a,double[] b) {
        double[] out=new double[16];
        for(int col=0;col<4;col++)
            for(int row=0;row<4;row++)
                for(int k=0;k<4;k++)out[col*4+row]+=a[k*4+row]*b[col*4+k];
        for(double d:out)finite(d,"matrix multiplication");
        return out;
    }
    private static double[] inverse(double[] input) {
        double[][] work=new double[4][8];
        for(int row=0;row<4;row++) {
            for(int col=0;col<4;col++)work[row][col]=input[col*4+row];
            work[row][4+row]=1;
        }
        for(int col=0;col<4;col++) {
            int pivot=col;
            for(int row=col+1;row<4;row++)
                if(Math.abs(work[row][col])>Math.abs(work[pivot][col]))pivot=row;
            if(Math.abs(work[pivot][col])<1e-12)
                throw new IllegalArgumentException("Skinned mesh transform is singular");
            double[] tmp=work[col];work[col]=work[pivot];work[pivot]=tmp;
            double scale=work[col][col];
            for(int i=0;i<8;i++)work[col][i]/=scale;
            for(int row=0;row<4;row++)if(row!=col) {
                double k=work[row][col];
                for(int i=0;i<8;i++)work[row][i]-=k*work[col][i];
            }
        }
        double[] result=new double[16];
        for(int col=0;col<4;col++)for(int row=0;row<4;row++)
            result[col*4+row]=finite(work[row][4+col],"inverse matrix");
        return result;
    }

    private static double[] sampled(String path,String interp,List<?> times,List<?> values,double time) {
        int n=times.size();
        int hi=0;
        while(hi<n && time>=num(times.get(hi)))hi++;
        if(hi==0)return toDouble(requiredList(values.get(0),"keyframe",4));
        if(hi==n)return toDouble(requiredList(values.get(n-1),"keyframe",4));
        if(interp.equals("STEP"))return toDouble(requiredList(values.get(hi-1),"keyframe",4));
        double start=num(times.get(hi-1)),end=num(times.get(hi));
        double alpha=(time-start)/(end-start);
        double[] a=toDouble(requiredList(values.get(hi-1),"keyframe",4));
        double[] b=toDouble(requiredList(values.get(hi),"keyframe",4));
        if(path.equals("rotation")) {
            double dot=0;
            for(int i=0;i<4;i++)dot+=a[i]*b[i];
            if(dot<0) {
                for(int i=0;i<4;i++)b[i]=-b[i];
                dot=-dot;
            }
            dot=Math.max(-1,Math.min(1,dot));
            double k0=1-alpha,k1=alpha;
            if(dot<0.9995) {
                double omega=Math.acos(dot),sin=Math.sin(omega);
                k0=Math.sin((1-alpha)*omega)/sin;
                k1=Math.sin(alpha*omega)/sin;
            }
            double[] result=new double[4];
            for(int i=0;i<4;i++)result[i]=k0*a[i]+k1*b[i];
            return normalizeQuaternion(list(result));
        }
        double[] result=new double[a.length];
        for(int i=0;i<a.length;i++)result[i]=a[i]+alpha*(b[i]-a[i]);
        return result;
    }
    private static double[] toDouble(List<?> values) {
        double[] out=new double[values.size()];
        for(int i=0;i<values.size();i++)out[i]=num(values.get(i));
        return out;
    }
    private static double[] world(int id,List<?> parents,List<double[]> locals,
                                  double[][] cache,byte[] state) {
        if(state[id]==2)return cache[id];
        if(state[id]==1)throw new IllegalArgumentException("Cycle in animated nodes");
        state[id]=1;
        int parent=(int)num(parents.get(id));
        if(parent>=parents.size() || parent<-1)
            throw new IllegalArgumentException("Parent node index invalid");
        double[] result=parent<0?locals.get(id):
                multiply(world(parent,parents,locals,cache,state),locals.get(id));
        state[id]=2;
        cache[id]=result;
        return result;
    }

    /**
     * Samples the first animation clip, returning one skin palette mat4/joint.
     * World transforms are computed in model-local space as:
     * inverse(meshWorld) * jointWorld * inverseBindMatrix.
     */
    static List<Object> sample(List<?> rig,double time) {
        finite(time,"animation time");
        if(rig==null || rig.size()!=14)
            throw new IllegalArgumentException("Animation rig needs 14 fields");
        List<?> parents=requiredList(rig.get(6),"parents",MAX_NODES);
        int n=parents.size();
        if(n<1)throw new IllegalArgumentException("Animation rig has no nodes");
        List<?> initialT=requiredList(rig.get(7),"translations",MAX_NODES);
        List<?> initialR=requiredList(rig.get(8),"rotations",MAX_NODES);
        List<?> initialS=requiredList(rig.get(9),"scales",MAX_NODES);
        if(initialT.size()!=n || initialR.size()!=n || initialS.size()!=n)
            throw new IllegalArgumentException("Animation bind TRS arrays mismatch");
        List<double[]> t=new ArrayList<>(),r=new ArrayList<>(),s=new ArrayList<>();
        for(int i=0;i<n;i++) {
            t.add(toDouble(requiredList(initialT.get(i),"translation",3)));
            r.add(toDouble(requiredList(initialR.get(i),"rotation",4)));
            s.add(toDouble(requiredList(initialS.get(i),"scale",3)));
        }
        List<?> channels=requiredList(rig.get(12),"channels",MAX_CHANNELS);
        for(Object entry:channels) {
            List<?> channel=requiredList(entry,"channel",5);
            if(channel.size()!=5)
                throw new IllegalArgumentException("Channel needs 5 fields");
            int target=(int)num(channel.get(0));
            if(target<0||target>=n)
                throw new IllegalArgumentException("Channel target out of range");
            String path=(String)channel.get(1),interp=(String)channel.get(2);
            if(!path.equals("translation")&&!path.equals("rotation")&&!path.equals("scale")
                    ||!interp.equals("LINEAR")&&!interp.equals("STEP"))
                throw new IllegalArgumentException("Unsupported animation path/interpolation");
            List<?> times=requiredList(channel.get(3),"times",MAX_KEYS);
            List<?> values=requiredList(channel.get(4),"values",MAX_KEYS);
            if(times.isEmpty()||times.size()!=values.size())
                throw new IllegalArgumentException("Keyframe count mismatch");
            double[] sampled=sampled(path,interp,times,values,time);
            switch(path) {
                case "translation" -> t.set(target,sampled);
                case "rotation" -> r.set(target,sampled);
                case "scale" -> s.set(target,sampled);
                default -> throw new AssertionError("Impossible path");
            }
        }
        List<double[]> local=new ArrayList<>();
        for(int i=0;i<n;i++)local.add(matrix(list(t.get(i)),list(r.get(i)),list(s.get(i))));
        double[][] cache=new double[n][];
        byte[] state=new byte[n];
        List<?> skinJoints=requiredList(rig.get(10),"skin joints",MAX_JOINTS);
        List<?> ibms=requiredList(rig.get(11),"inverse bind matrices",MAX_JOINTS);
        if(skinJoints.isEmpty()||skinJoints.size()!=ibms.size())
            throw new IllegalArgumentException("Skin palette length mismatch");
        int meshNode=(int)num(rig.get(13));
        if(meshNode<0||meshNode>=n)throw new IllegalArgumentException("Mesh node invalid");
        double[] meshWorld=world(meshNode,parents,local,cache,state);
        double[] worldInverse=inverse(meshWorld);
        List<Object> palette=new ArrayList<>();
        for(int i=0;i<skinJoints.size();i++) {
            int joint=(int)num(skinJoints.get(i));
            if(joint<0||joint>=n)throw new IllegalArgumentException("Joint node invalid");
            List<?> bind=requiredList(ibms.get(i),"inverse bind",16);
            if(bind.size()!=16)throw new IllegalArgumentException("Inverse bind must be mat4");
            palette.add(list(multiply(multiply(worldInverse,
                    world(joint,parents,local,cache,state)),toDouble(bind))));
        }
        return palette;
    }
}

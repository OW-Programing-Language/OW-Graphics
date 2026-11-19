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
    private static final int MAX_CLIPS = 16;
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
        return rigFromLibrary(loadLibraryRelative(filename,root));
    }

    static List<Object> loadLibraryRelative(String filename,Path root) {
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
            return lower.endsWith(".glb")?libraryFromDocument(GltfSceneParser.readGlb(Files.readAllBytes(file))):
                    parseLibraryJson(Files.readString(file,java.nio.charset.StandardCharsets.UTF_8));
        } catch(IOException e) {
            throw new IllegalArgumentException("Cannot read glTF animation asset",e);
        }
    }
    static List<Object> parseJson(String json) {
        return rigFromLibrary(parseLibraryJson(json));
    }

    static List<Object> parseLibraryJson(String json) {
        return libraryFromDocument(GltfModelParser.document(json));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> rigFromLibrary(List<Object> library) {
        return (List<Object>) library.get(0);
    }

    static List<Object> fromDocument(GltfModelParser.EmbeddedDocument doc) {
        return rigFromLibrary(libraryFromDocument(doc));
    }

    static List<Object> libraryFromDocument(GltfModelParser.EmbeddedDocument doc) {
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
        if(animations.isEmpty() || animations.size()>MAX_CLIPS)
            throw new IllegalArgumentException("Animated glTF must contain 1..16 clips");
        List<Object> allClips=new ArrayList<>(animations.size());
        List<Object> clipNames=new ArrayList<>(animations.size());
        List<Object> clipDurations=new ArrayList<>(animations.size());
        for(int clipIndex=0;clipIndex<animations.size();clipIndex++) {
            JsonObject animation=obj(animations.get(clipIndex),"animation");
            JsonArray samplers=arr(animation,"samplers");
            JsonArray channels=arr(animation,"channels");
            if(channels.isEmpty() || channels.size()>MAX_CHANNELS)
                throw new IllegalArgumentException("Animation clip needs 1..32 channels");
            List<Object> clip=new ArrayList<>();
            Set<String> channelsUsed=new HashSet<>();
            double duration=0;
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
                    throw new IllegalArgumentException("Duplicate animated node/path in one clip");
                String interpolation=sampler.has("interpolation")?
                        sampler.get("interpolation").getAsString():"LINEAR";
                if(!interpolation.equals("LINEAR") && !interpolation.equals("STEP")
                        && !interpolation.equals("CUBICSPLINE"))
                    throw new IllegalArgumentException("Only LINEAR, STEP and CUBICSPLINE samplers supported");
                GltfModelParser.Accessor in=GltfModelParser.accessor(root,doc.data(),integer(sampler,"input"));
                GltfModelParser.Accessor out=GltfModelParser.accessor(root,doc.data(),integer(sampler,"output"));
                int components=path.equals("rotation")?4:3;
                int outputsPerKey=interpolation.equals("CUBICSPLINE")?3:1;
                if(in.components()!=1 || in.componentType()!=5126 || out.components()!=components
                        || out.componentType()!=5126 || in.count()*outputsPerKey!=out.count()
                        || in.count()>MAX_KEYS)
                    throw new IllegalArgumentException("Animation needs FLOAT times and matching VEC3/VEC4 samples/tangents");
                List<Double> times=new ArrayList<>();
                List<Object> keyValues=new ArrayList<>();
                for(int k=0;k<in.count();k++) {
                    double t=in.value(k,0);
                    if(t<0 || k>0 && t<=times.get(k-1))
                        throw new IllegalArgumentException("Animation keyframe time must strictly increase");
                    times.add(t);
                    if(interpolation.equals("CUBICSPLINE")) {
                        List<Object> triplet=new ArrayList<>(3);
                        for(int slot=0;slot<3;slot++) {
                            List<Double> values=new ArrayList<>(components);
                            for(int c=0;c<components;c++)values.add(out.value(k*3+slot,c));
                            // glTF cubic derivatives are tangent vectors, NOT unit quaternions.
                            // Only the value slot of a quaternion sample is normalized.
                            triplet.add(path.equals("rotation") && slot==1?
                                    list(normalizeQuaternion(values)):values);
                        }
                        keyValues.add(triplet);
                    } else {
                        List<Double> values=new ArrayList<>(components);
                        for(int c=0;c<components;c++)values.add(out.value(k,c));
                        keyValues.add(path.equals("rotation")?list(normalizeQuaternion(values)):values);
                    }
                }
                duration=Math.max(duration,times.get(times.size()-1));
                clip.add(List.of(node,path,interpolation,times,keyValues));
            }
            String name=animation.has("name")?animation.get("name").getAsString():"Clip "+clipIndex;
            if(name.isBlank() || name.length()>128)
                throw new IllegalArgumentException("Animation clip names must be 1..128 chars");
            allClips.add(clip);
            clipNames.add(name);
            clipDurations.add(duration);
        }
        List<Object> result=new ArrayList<>(14);
        result.add(geometry.get(0));result.add(geometry.get(1));
        result.add(geometry.get(2));result.add(geometry.get(3));
        result.add(jointOut);result.add(weightOut);
        result.add(parents);result.add(translations);result.add(rotations);result.add(scales);
        result.add(skinJoints);result.add(inverseBinds);result.add(allClips.get(0));result.add(meshNode);
        return List.of(result,allClips,clipNames,clipDurations);
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

    private static double[] key(String interpolation,List<?> values,int index) {
        Object item=values.get(index);
        if(interpolation.equals("CUBICSPLINE")) {
            List<?> triple=requiredList(item,"cubic keyframe",3);
            if(triple.size()!=3)
                throw new IllegalArgumentException("Cubic keyframe needs in/value/out");
            item=triple.get(1);
        }
        return toDouble(requiredList(item,"keyframe",4));
    }
    private static double[] tangent(List<?> values,int index,int slot) {
        List<?> triple=requiredList(values.get(index),"cubic triplet",3);
        if(triple.size()!=3)
            throw new IllegalArgumentException("Cubic keyframe needs in/value/out");
        return toDouble(requiredList(triple.get(slot),"cubic tangent",4));
    }

    /** Shortest-arc quaternion interpolation for rotation channels and crossfades. */
    private static double[] slerp(double[] from,double[] to,double factor) {
        if(from.length!=4||to.length!=4)
            throw new IllegalArgumentException("SLERP requires quaternion VEC4");
        double[] a=normalizeQuaternion(list(from)),b=normalizeQuaternion(list(to));
        double dot=0;
        for(int i=0;i<4;i++)dot+=a[i]*b[i];
        if(dot<0) {
            for(int i=0;i<4;i++)b[i]=-b[i];
            dot=-dot;
        }
        dot=Math.max(-1,Math.min(1,dot));
        double k0=1-factor,k1=factor;
        if(dot<0.9995) {
            double omega=Math.acos(dot),sin=Math.sin(omega);
            k0=Math.sin((1-factor)*omega)/sin;
            k1=Math.sin(factor*omega)/sin;
        }
        double[] mixed=new double[4];
        for(int i=0;i<4;i++)mixed[i]=k0*a[i]+k1*b[i];
        return normalizeQuaternion(list(mixed));
    }

    private static double[] sampled(String path,String interp,List<?> times,List<?> values,double time) {
        int n=times.size();
        int hi=0;
        while(hi<n && time>=num(times.get(hi)))hi++;
        if(hi==0)return key(interp,values,0);
        if(hi==n)return key(interp,values,n-1);
        if(interp.equals("STEP"))return key(interp,values,hi-1);

        double start=num(times.get(hi-1)),end=num(times.get(hi));
        if(end<=start)throw new IllegalArgumentException("Animation times must strictly increase");
        double alpha=(time-start)/(end-start);
        double[] a=key(interp,values,hi-1),b=key(interp,values,hi);
        if(interp.equals("CUBICSPLINE")) {
            double[] out=tangent(values,hi-1,2),in=tangent(values,hi,0);
            if(out.length!=a.length||in.length!=a.length||b.length!=a.length)
                throw new IllegalArgumentException("Cubic tangent dimension mismatch");
            double t2=alpha*alpha,t3=t2*alpha;
            double h00=2*t3-3*t2+1,h10=t3-2*t2+alpha;
            double h01=-2*t3+3*t2,h11=t3-t2;
            double dt=end-start;
            double[] result=new double[a.length];
            for(int i=0;i<a.length;i++)
                result[i]=finite(h00*a[i]+h10*dt*out[i]+h01*b[i]+h11*dt*in[i],
                        "CUBICSPLINE sample");
            return path.equals("rotation")?normalizeQuaternion(list(result)):result;
        }
        if(interp.equals("LINEAR")) {
            if(path.equals("rotation"))return slerp(a,b,alpha);
            double[] result=new double[a.length];
            for(int i=0;i<a.length;i++)
                result[i]=finite(a[i]+alpha*(b[i]-a[i]),"LINEAR sample");
            return result;
        }
        throw new IllegalArgumentException("Unknown animation interpolation");
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

    /** One sampled TRS pose per glTF node, not a blended matrix palette. */
    private record Pose(List<double[]> translations,List<double[]> rotations,List<double[]> scales) {}

    private static Pose pose(List<?> rig,double time) {
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
                    ||!interp.equals("LINEAR")&&!interp.equals("STEP")&&!interp.equals("CUBICSPLINE"))
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

        return new Pose(t,r,s);
    }

    /** Return the mesh-local skinning palette for an already blended TRS pose. */
    private static List<Object> palette(List<?> rig,Pose pose) {
        List<?> parents=requiredList(rig.get(6),"parents",MAX_NODES);
        int n=parents.size();
        if(pose.translations().size()!=n || pose.rotations().size()!=n || pose.scales().size()!=n)
            throw new IllegalArgumentException("Sampled pose node counts mismatch");
        List<double[]> local=new ArrayList<>();
        for(int i=0;i<n;i++)local.add(matrix(list(pose.translations().get(i)),list(pose.rotations().get(i)),list(pose.scales().get(i))));
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

    /** Old Phase 9 single-clip entry point is intentionally backward compatible. */
    static List<Object> sample(List<?> rig,double time) {
        return palette(rig,pose(rig,time));
    }

    private static List<?> library(List<?> lib) {
        if(lib==null || lib.size()!=4)
            throw new IllegalArgumentException("Animation library needs rig, clips, names and durations");
        List<?> rig=requiredList(lib.get(0),"library rig",14);
        if(rig.size()!=14)throw new IllegalArgumentException("Base rig needs 14 fields");
        return rig;
    }
    private static List<?> clip(List<?> lib,int index) {
        library(lib);
        List<?> clips=requiredList(lib.get(1),"animation clips",MAX_CLIPS);
        if(index<0||index>=clips.size())
            throw new IllegalArgumentException("Clip index out of range");
        return requiredList(clips.get(index),"clip channels",MAX_CHANNELS);
    }
    private static List<Object> rigClip(List<?> lib,int clipId) {
        List<?> rig=library(lib);
        List<?> channels=clip(lib,clipId);
        List<Object> chosen=new ArrayList<>(rig);
        chosen.set(12,channels);
        return chosen;
    }

    static double clipDuration(List<?> lib,int index) {
        clip(lib,index);
        List<?> durations=requiredList(lib.get(3),"clip durations",MAX_CLIPS);
        if(index>=durations.size())
            throw new IllegalArgumentException("Clip duration missing");
        double duration=num(durations.get(index));
        if(duration<0)throw new IllegalArgumentException("Invalid clip duration");
        return duration;
    }
    /** Time wrapping is opt-in; raw sample_clip clamps to animation endpoints. */
    static double loopTime(List<?> lib,int index,double seconds) {
        finite(seconds,"elapsed animation time");
        double duration=clipDuration(lib,index);
        if(duration<=0)return 0;
        double wrapped=seconds%duration;
        if(wrapped<0)wrapped+=duration;
        return wrapped;
    }

    static List<Object> sampleClip(List<?> lib,int index,double seconds) {
        return sample(rigClip(lib,index),seconds);
    }

    /**
     * Blend sampled per-node local translation/scale and quaternion rotation;
     * do not lerp matrices/joint palettes, which breaks rigid rotations.
     */
    static List<Object> blendClips(List<?> lib,int first,double firstTime,
                                   int second,double secondTime,double alpha) {
        if(!Double.isFinite(alpha)||alpha<0||alpha>1)
            throw new IllegalArgumentException("Crossfade alpha must be in [0,1]");
        List<?> rig=library(lib);
        if(alpha==0)return sampleClip(lib,first,firstTime);
        if(alpha==1)return sampleClip(lib,second,secondTime);
        Pose a=pose(rigClip(lib,first),firstTime);
        Pose b=pose(rigClip(lib,second),secondTime);
        int n=a.translations().size();
        List<double[]> t=new ArrayList<>(),r=new ArrayList<>(),s=new ArrayList<>();
        for(int i=0;i<n;i++) {
            double[] at=a.translations().get(i),bt=b.translations().get(i);
            double[] as=a.scales().get(i),bs=b.scales().get(i);
            double[] mixedT=new double[3],mixedS=new double[3];
            for(int c=0;c<3;c++) {
                mixedT[c]=finite(at[c]+alpha*(bt[c]-at[c]),"blended translation");
                mixedS[c]=finite(as[c]+alpha*(bs[c]-as[c]),"blended scale");
            }
            t.add(mixedT);
            s.add(mixedS);
            r.add(slerp(a.rotations().get(i),b.rotations().get(i),alpha));
        }
        return palette(rig,new Pose(t,r,s));
    }

    /** glTF uses xyzw unit quaternions. Do not blend skinned matrices directly. */
    private static double[] quaternionMultiply(double[] a,double[] b) {
        double x=a[3]*b[0]+a[0]*b[3]+a[1]*b[2]-a[2]*b[1];
        double y=a[3]*b[1]-a[0]*b[2]+a[1]*b[3]+a[2]*b[0];
        double z=a[3]*b[2]+a[0]*b[1]-a[1]*b[0]+a[2]*b[3];
        double w=a[3]*b[3]-a[0]*b[0]-a[1]*b[1]-a[2]*b[2];
        return normalizeQuaternion(List.of(x,y,z,w));
    }

    /**
     * Add the difference between an overlay clip and its reference pose onto
     * a base clip, restricted to explicit glTF node IDs. Translations are
     * additive, scales multiplicative relative to reference, rotations use
     * quaternion multiplication and weighted SLERP from identity.
     *
     * The rig's mesh-local bone palette is computed ONLY after TRS layering.
     */
    static List<Object> additiveClips(List<?> lib,int baseClip,double baseTime,
                                      int layerClip,double layerTime,double referenceTime,
                                      double weight,List<?> mask) {
        finite(weight,"additive weight");
        if(weight<0||weight>1)
            throw new IllegalArgumentException("Additive weight must be [0,1]");
        Pose base=pose(rigClip(lib,baseClip),baseTime);
        Pose layer=pose(rigClip(lib,layerClip),layerTime);
        Pose reference=pose(rigClip(lib,layerClip),referenceTime);
        int size=base.translations().size();
        if(mask==null||mask.size()>size)
            throw new IllegalArgumentException("Node mask must be a bounded array");
        boolean[] selected=new boolean[size];
        for(Object entry:mask) {
            double raw=num(entry);
            if(raw!=Math.rint(raw)||raw<0||raw>=size)
                throw new IllegalArgumentException("Additive mask index out of range");
            int id=(int)raw;
            if(selected[id])throw new IllegalArgumentException("Additive mask contains duplicate nodes");
            selected[id]=true;
        }
        List<double[]> translations=new ArrayList<>(size);
        List<double[]> rotations=new ArrayList<>(size);
        List<double[]> scales=new ArrayList<>(size);
        for(int i=0;i<size;i++) {
            double[] bt=base.translations().get(i),br=base.rotations().get(i),
                    bs=base.scales().get(i);
            if(!selected[i]||weight==0) {
                translations.add(bt);rotations.add(br);scales.add(bs);
                continue;
            }
            double[] lt=layer.translations().get(i),lr=layer.rotations().get(i),
                    ls=layer.scales().get(i),rt=reference.translations().get(i),
                    rr=reference.rotations().get(i),rs=reference.scales().get(i);
            double[] targetT=new double[3],targetS=new double[3];
            for(int c=0;c<3;c++) {
                targetT[c]=finite(bt[c]+weight*(lt[c]-rt[c]),"additive translation");
                if(Math.abs(rs[c])<1e-9)
                    throw new IllegalArgumentException("Reference scale must be invertible");
                targetS[c]=finite(bs[c]*(1+weight*(ls[c]/rs[c]-1)),"additive scale");
            }
            double[] inverseReference={-rr[0],-rr[1],-rr[2],rr[3]};
            double[] delta=quaternionMultiply(lr,inverseReference);
            double[] weighted=slerp(new double[]{0,0,0,1},delta,weight);
            translations.add(targetT);
            scales.add(targetS);
            rotations.add(quaternionMultiply(br,weighted));
        }
        return palette(library(lib),new Pose(translations,rotations,scales));
    }

    /**
     * Displacement in the selected node's LOCAL translation, including cycle
     * crossings. This is a motion-extraction API, not automatic character
     * movement or a full world-space root-motion integrator.
     */
    static List<Double> rootDelta(List<?> lib,int clipId,int node,
                                  double previous,double current) {
        finite(previous,"previous root-motion time");
        finite(current,"current root-motion time");
        if(previous<0||current<previous||current-previous>3600)
            throw new IllegalArgumentException("Root-motion times must be ordered, range <=3600s");
        List<?> rig=rigClip(lib,clipId);
        int size=requiredList(rig.get(6),"nodes",MAX_NODES).size();
        if(node<0||node>=size)
            throw new IllegalArgumentException("Root-motion node out of range");
        double duration=clipDuration(lib,clipId);
        if(duration==0)return List.of(0.0,0.0,0.0);
        double cycles=Math.floor(current/duration)-Math.floor(previous/duration);
        if(cycles>10000)
            throw new IllegalArgumentException("Too many animation cycles in one query");
        double[] start=pose(rig,loopTime(lib,clipId,previous)).translations().get(node);
        double[] end=pose(rig,loopTime(lib,clipId,current)).translations().get(node);
        double[] zero=pose(rig,0).translations().get(node);
        double[] finalValue=pose(rig,duration).translations().get(node);
        List<Double> delta=new ArrayList<>(3);
        for(int i=0;i<3;i++)
            delta.add(finite(end[i]-start[i]+cycles*(finalValue[i]-zero[i]),
                    "root-motion displacement"));
        return delta;
    }

    /**
     * User-authored animation markers (NOT a standard glTF event extension):
     * [clipIndex, localSeconds, eventName]. Return [name, absoluteTime, markerTime]
     * for occurrences in the half-open (previous, current] time interval.
     * Traverses a bounded number of loops, never emits network/event callbacks.
     */
    static List<Object> eventsBetween(List<?> lib,int clipId,List<?> markers,
                                      double previous,double current) {
        finite(previous,"previous event time");
        finite(current,"current event time");
        if(previous<0||current<previous||current-previous>3600)
            throw new IllegalArgumentException("Event query requires ordered bounded times");
        if(markers==null||markers.size()>128)
            throw new IllegalArgumentException("Markers must contain <=128 entries");
        double duration=clipDuration(lib,clipId);
        if(duration<=0)return List.of();
        double fromCycle=Math.floor(previous/duration);
        double toCycle=Math.floor(current/duration);
        if(toCycle-fromCycle>128)
            throw new IllegalArgumentException("Event scan crossed too many animation cycles");
        List<Object> events=new ArrayList<>();
        // Source order is stable per loop; callers can use the returned times
        // to sort different markers if they need globally chronological order.
        for(double cycle=fromCycle;cycle<=toCycle;cycle++) {
            for(Object item:markers) {
                List<?> marker=requiredList(item,"animation marker",3);
                if(marker.size()!=3)
                    throw new IllegalArgumentException("Marker must have clip, seconds, name");
                double idValue=num(marker.get(0));
                if(idValue!=Math.rint(idValue)||idValue<0||idValue>=
                        requiredList(lib.get(1),"clips",MAX_CLIPS).size())
                    throw new IllegalArgumentException("Marker clip index invalid");
                double at=num(marker.get(1));
                if(at<0||at>clipDuration(lib,(int)idValue))
                    throw new IllegalArgumentException("Marker must be within the clip duration");
                if(!(marker.get(2) instanceof String name)||name.isBlank()||name.length()>128)
                    throw new IllegalArgumentException("Marker event name must be 1..128 characters");
                if((int)idValue!=clipId)continue;
                double absolute=cycle*duration+at;
                if(absolute>previous && absolute<=current) {
                    if(events.size()>=128)
                        throw new IllegalArgumentException("Too many animation events in one query");
                    events.add(List.of(name,absolute,at));
                }
            }
        }
        return events;
    }

}

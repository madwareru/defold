package com.dynamo.bob.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.vecmath.AxisAngle4d;
import javax.vecmath.Point3d;
import javax.vecmath.Quat4d;
import javax.vecmath.Vector3d;

import org.apache.commons.lang.ArrayUtils;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;

import com.dynamo.bob.util.SpineScene.AnimationTrack.Property;

/**
 * Convenience class for loading spine json data.
 *
 * Should preferably have been an extension to Bob rather than located inside it.
 */
public class SpineScene {
    @SuppressWarnings("serial")
    public static class LoadException extends Exception {
        public LoadException(String msg) {
            super(msg);
        }
    }

    public static class Transform {
        public Point3d position = new Point3d();
        public Quat4d rotation = new Quat4d();
        public Vector3d scale = new Vector3d();

        public Transform() {
            this.position.set(0.0, 0.0, 0.0);
            this.rotation.set(0.0, 0.0, 0.0, 1.0);
            this.scale.set(1.0, 1.0, 1.0);
        }

        public Transform(Transform t) {
            set(t);
        }

        public void set(Transform t) {
            this.position.set(t.position);
            this.rotation.set(t.rotation);
            this.scale.set(t.scale);
        }

        public void setZAngleDeg(double angle) {
            double rads = angle * Math.PI / 180.0;
            this.rotation.set(new AxisAngle4d(new Vector3d(0.0, 0.0, 1.0), rads));
        }

        private static void rotate(Quat4d rotation, Point3d p) {
            // The length is needed for the up-scaling at the end
            // Quat4d automatically normalizes in the constructor so this info is lost below
            double length = Math.sqrt(p.x * p.x + p.y * p.y + p.z * p.z);
            Quat4d qp = new Quat4d(p.x, p.y, p.z, 0.0);
            qp.mul(rotation, qp);
            qp.mulInverse(rotation);
            p.set(qp.x, qp.y, qp.z);
            p.scale(length);
        }

        public void apply(Point3d p) {
            p.set(this.scale.x * p.x, this.scale.y * p.y, this.scale.z * p.z);
            rotate(this.rotation, p);
            p.add(this.position);
        }

        public void mul(Transform t) {
            // Scale and rotate position
            Point3d p = new Point3d(t.position);
            apply(p);
            this.position.set(p);
            // Intentionally non-rotated scale to avoid shearing in the transform
            this.scale.set(this.scale.x * t.scale.x, this.scale.y * t.scale.y, this.scale.z * t.scale.z);
            // Rotation
            this.rotation.mul(t.rotation);
        }

        public void inverse() {
            this.scale.set(1.0 / this.scale.x, 1.0 / this.scale.y, 1.0 / this.scale.z);
            this.rotation.conjugate();
            Point3d p = new Point3d(this.position);
            p.scale(-1.0);
            this.position.set(0.0, 0.0, 0.0);
            apply(p);
            this.position.set(p);
        }
    }

    public static class Bone {
        public String name = "";
        public Transform localT = new Transform();
        public Transform worldT = new Transform();
        public Transform invWorldT = new Transform();
        public Bone parent = null;
        public int index = -1;
        public boolean inheritScale = true;
    }

    public static class Mesh {
        public String attachment;
        public String path;
        public Slot slot;
        // format is: x0, y0, z0, u0, v0, ...
        public float[] vertices;
        public int[] triangles;
        public float[] boneWeights;
        public int[] boneIndices;
    }

    private static class Slot {
        public Bone bone;
        public int index;
        public String attachment;

        public Slot(Bone bone, int index, String attachment) {
            this.bone = bone;
            this.index = index;
            this.attachment = attachment;
        }
    }

    public static class AnimationCurve {
        public float x0;
        public float y0;
        public float x1;
        public float y1;
    }

    public static class AnimationKey {
        public float t;
        public float[] value = new float[4];
        public AnimationCurve curve;
    }

    public static class AnimationTrack {
        public enum Property {
            POSITION, ROTATION, SCALE
        }
        public Bone bone;
        public Property property;
        public List<AnimationKey> keys = new ArrayList<AnimationKey>();
    }

    public static class Animation {
        public String name;
        public float duration;
        public List<AnimationTrack> tracks = new ArrayList<AnimationTrack>();
    }

    public List<Bone> bones = new ArrayList<Bone>();
    public Map<String, Bone> nameToBones = new HashMap<String, Bone>();
    public List<Mesh> meshes = new ArrayList<Mesh>();
    public Map<String, List<Mesh>> skins = new HashMap<String, List<Mesh>>();
    public Map<String, Animation> animations = new HashMap<String, Animation>();

    public Bone getBone(String name) {
        return nameToBones.get(name);
    }

    public Bone getBone(int index) {
        return bones.get(index);
    }

    public Animation getAnimation(String name) {
        return animations.get(name);
    }

    private static void loadTransform(JsonNode node, Transform t) {
        t.position.set(JsonUtil.get(node, "x", 0.0), JsonUtil.get(node, "y", 0.0), 0.0);
        t.setZAngleDeg(JsonUtil.get(node, "rotation", 0.0));
        t.scale.set(JsonUtil.get(node, "scaleX", 1.0), JsonUtil.get(node, "scaleY", 1.0), 1.0);
    }

    private void loadBone(JsonNode boneNode) throws LoadException {
        Bone bone = new Bone();
        bone.name = boneNode.get("name").asText();
        bone.index = this.bones.size();
        if (boneNode.has("inheritScale")) {
            bone.inheritScale = boneNode.get("inheritScale").asBoolean();
        }
        loadTransform(boneNode, bone.localT);
        if (boneNode.has("parent")) {
            String parentName = boneNode.get("parent").asText();
            bone.parent = getBone(parentName);
            if (bone.parent == null) {
                throw new LoadException(String.format("The parent bone '%s' does not exist.", parentName));
            }
            bone.worldT.set(bone.parent.worldT);
            bone.worldT.mul(bone.localT);
            // Restore scale to local when it shouldn't be inherited
            if (!bone.inheritScale) {
                bone.worldT.scale.set(bone.localT.scale);
            }
        } else {
            bone.worldT.set(bone.localT);
        }
        bone.invWorldT.set(bone.worldT);
        bone.invWorldT.inverse();
        this.bones.add(bone);
        this.nameToBones.put(bone.name, bone);
    }

    private void loadRegion(JsonNode attNode, Mesh mesh, Bone bone) {
        Transform world = new Transform(bone.worldT);
        Transform local = new Transform();
        loadTransform(attNode, local);
        world.mul(local);
        mesh.vertices = new float[4 * 5];
        double width = JsonUtil.get(attNode, "width", 0.0);
        double height = JsonUtil.get(attNode, "height", 0.0);
        double[] boundary = new double[] {-0.5, 0.5};
        double[] uv_boundary = new double[] {0.0, 1.0};
        int i = 0;
        for (int xi = 0; xi < 2; ++xi) {
            for (int yi = 0; yi < 2; ++yi) {
                Point3d p = new Point3d(boundary[xi] * width, boundary[yi] * height, 0.0);
                world.apply(p);
                mesh.vertices[i++] = (float)p.x;
                mesh.vertices[i++] = (float)p.y;
                mesh.vertices[i++] = (float)p.z;
                mesh.vertices[i++] = (float)uv_boundary[xi];
                mesh.vertices[i++] = (float)(1.0 - uv_boundary[yi]);
            }
        }
        mesh.triangles = new int[] {
                0, 1, 2,
                2, 1, 3
        };
    }

    private void loadMesh(JsonNode attNode, Mesh mesh, Bone bone, boolean skinned) {
        int vertexCount = attNode.get("hull").asInt();
        Iterator<JsonNode> vertexIt = attNode.get("vertices").getElements();
        Iterator<JsonNode> uvIt = attNode.get("uvs").getElements();
        mesh.vertices = new float[vertexCount * 5];
        if (skinned) {
            mesh.boneIndices = new int[vertexCount * 4];
            mesh.boneWeights = new float[vertexCount * 4];
        }
        for (int i = 0; i < vertexCount; ++i) {
            Point3d p = null;
            if (skinned) {
                int boneCount = vertexIt.next().asInt();
                int boneOffset = i*4;
                for (int bi = 0; bi < boneCount; ++bi) {
                    int boneIndex = vertexIt.next().asInt();
                    double x = vertexIt.next().asDouble();
                    double y = vertexIt.next().asDouble();
                    double weight = vertexIt.next().asDouble();
                    // Vertex is skinned, ignore supplied bone and use the first skinned bone to retrieve model space coordinates
                    if (p == null) {
                        bone = getBone(boneIndex);
                        p = new Point3d(x, y, 0.0);
                    }
                    mesh.boneIndices[boneOffset+bi] = boneIndex;
                    mesh.boneWeights[boneOffset+bi] = (float)weight;
                }
            } else {
                double x = vertexIt.next().asDouble();
                double y = vertexIt.next().asDouble();
                p = new Point3d(x, y, 0.0);
            }
            bone.worldT.apply(p);
            int vi = i*5;
            mesh.vertices[vi++] = (float)p.x;
            mesh.vertices[vi++] = (float)p.y;
            mesh.vertices[vi++] = (float)p.z;
            mesh.vertices[vi++] = (float)uvIt.next().asDouble();
            mesh.vertices[vi++] = (float)uvIt.next().asDouble();
        }
        Iterator<JsonNode> triangleIt = attNode.get("triangles").getElements();
        List<Integer> triangles = new ArrayList<Integer>(vertexCount);
        while (triangleIt.hasNext()) {
            for (int i = 0; i < 3; ++i) {
                triangles.add(triangleIt.next().asInt());
            }
        }
        mesh.triangles = ArrayUtils.toPrimitive(triangles.toArray(new Integer[triangles.size()]));
    }

    public static SpineScene loadJson(InputStream is) throws LoadException {
        SpineScene scene = new SpineScene();
        ObjectMapper m = new ObjectMapper();
        try {
            JsonNode node = m.readValue(new InputStreamReader(is, "UTF-8"), JsonNode.class);
            Iterator<JsonNode> boneIt = node.get("bones").getElements();
            while (boneIt.hasNext()) {
                JsonNode boneNode = boneIt.next();
                scene.loadBone(boneNode);
            }
            final Map<String, Slot> slots = new HashMap<String, Slot>();
            int slotIndex = 0;
            Iterator<JsonNode> slotIt = node.get("slots").getElements();
            while (slotIt.hasNext()) {
                JsonNode slotNode = slotIt.next();
                String attachment = JsonUtil.get(slotNode, "attachment", (String)null);
                if (attachment != null) {
                    String boneName = slotNode.get("bone").asText();
                    Bone bone = scene.getBone(boneName);
                    if (bone == null) {
                        throw new LoadException(String.format("The bone '%s' of attachment '%s' does not exist.", boneName, attachment));
                    }
                    slots.put(slotNode.get("name").asText(), new Slot(bone, slotIndex, attachment));
                    ++slotIndex;
                }
            }
            Iterator<Map.Entry<String, JsonNode>> skinIt = node.get("skins").getFields();
            while (skinIt.hasNext()) {
                Map.Entry<String, JsonNode> entry = skinIt.next();
                String skinName = entry.getKey();
                JsonNode skinNode = entry.getValue();
                Iterator<Map.Entry<String, JsonNode>> skinSlotIt = skinNode.getFields();
                List<Mesh> meshes = new ArrayList<Mesh>();
                while (skinSlotIt.hasNext()) {
                    Map.Entry<String, JsonNode> slotEntry = skinSlotIt.next();
                    String slotName = slotEntry.getKey();
                    Slot slot = slots.get(slotName);
                    JsonNode slotNode = slotEntry.getValue();
                    Iterator<Map.Entry<String, JsonNode>> attIt = slotNode.getFields();
                    while (attIt.hasNext()) {
                        Map.Entry<String, JsonNode> attEntry = attIt.next();
                        String attName = attEntry.getKey();
                        if (attName.equals(slot.attachment)) {
                            JsonNode attNode = attEntry.getValue();
                            Bone bone = slot.bone;
                            if (bone == null) {
                                throw new LoadException(String.format("No bone mapped to attachment '%s'.", attName));
                            }
                            String path = attName;
                            if (attNode.has("name")) {
                                path = attNode.get("name").asText();
                            }
                            String type = JsonUtil.get(attNode, "type", "region");
                            Mesh mesh = new Mesh();
                            mesh.attachment = attName;
                            mesh.path = path;
                            mesh.slot = slot;
                            if (type.equals("region")) {
                                scene.loadRegion(attNode, mesh, bone);
                            } else if (type.equals("mesh")) {
                                scene.loadMesh(attNode, mesh, bone, false);
                            } else if (type.equals("skinnedmesh")) {
                                scene.loadMesh(attNode, mesh, bone, true);
                            } else {
                                mesh = null;
                            }
                            // Silently ignore unsupported types
                            if (mesh != null) {
                                meshes.add(mesh);
                            }
                        }
                    }
                }
                Collections.sort(meshes, new Comparator<Mesh>() {
                    @Override
                    public int compare(Mesh o1, Mesh o2) {
                        return o1.slot.index - o2.slot.index;
                    }
                });
                // Special handling of the default skin
                if (skinName.equals("default")) {
                    scene.meshes = meshes;
                } else {
                    scene.skins.put(skinName, meshes);
                }
            }
            Iterator<Map.Entry<String, JsonNode>> animationIt = node.get("animations").getFields();
            while (animationIt.hasNext()) {
                Map.Entry<String, JsonNode> entry = animationIt.next();
                String animName = entry.getKey();
                JsonNode animNode = entry.getValue();
                Animation animation = new Animation();
                animation.name = animName;
                double duration = 0.0;
                Iterator<Map.Entry<String, JsonNode>> animBoneIt = animNode.get("bones").getFields();
                while (animBoneIt.hasNext()) {
                    Map.Entry<String, JsonNode> boneEntry = animBoneIt.next();
                    String boneName = boneEntry.getKey();
                    JsonNode boneNode = boneEntry.getValue();
                    Iterator<Map.Entry<String, JsonNode>> propIt = boneNode.getFields();
                    while (propIt.hasNext()) {
                        Map.Entry<String, JsonNode> propEntry = propIt.next();
                        String propName = propEntry.getKey();
                        JsonNode propNode = propEntry.getValue();
                        AnimationTrack track = new AnimationTrack();
                        track.bone = scene.getBone(boneName);
                        track.property = spineToProperty(propName);
                        Iterator<JsonNode> keyIt = propNode.getElements();
                        while (keyIt.hasNext()) {
                            JsonNode keyNode =  keyIt.next();
                            double time = keyNode.get("time").asDouble();
                            duration = Math.max(duration, time);
                            AnimationKey key = new AnimationKey();
                            key.t = (float)time;
                            switch (track.property) {
                            case POSITION:
                                key.value = new float[] {JsonUtil.get(keyNode, "x", 0.0f), JsonUtil.get(keyNode, "y", 0.0f)};
                                break;
                            case ROTATION:
                                key.value = new float[] {JsonUtil.get(keyNode, "angle", 0.0f)};
                                break;
                            case SCALE:
                                key.value = new float[] {JsonUtil.get(keyNode, "x", 0.0f), JsonUtil.get(keyNode, "y", 0.0f)};
                                break;
                            }
                            if (keyNode.has("curve")) {
                                AnimationCurve curve = new AnimationCurve();
                                Iterator<JsonNode> curveIt = keyNode.get("curve").getElements();
                                curve.x0 = (float)curveIt.next().asDouble();
                                curve.y0 = (float)curveIt.next().asDouble();
                                curve.x1 = (float)curveIt.next().asDouble();
                                curve.y1 = (float)curveIt.next().asDouble();
                                key.curve = curve;
                            }
                            track.keys.add(key);
                        }
                        animation.tracks.add(track);
                    }
                }
                animation.duration = (float)duration;
                scene.animations.put(animName, animation);
            }
            return scene;
        } catch (JsonParseException e) {
            throw new LoadException(e.getMessage());
        } catch (JsonMappingException e) {
            throw new LoadException(e.getMessage());
        } catch (UnsupportedEncodingException e) {
            throw new LoadException(e.getMessage());
        } catch (IOException e) {
            throw new LoadException(e.getMessage());
        }
    }

    public static Property spineToProperty(String name) {
        if (name.equals("translate")) {
            return Property.POSITION;
        } else if (name.equals("rotate")) {
            return Property.ROTATION;
        } else if (name.equals("scale")) {
            return Property.SCALE;
        }
        return null;
    }

    public static class JsonUtil {

        public static double get(JsonNode n, String name, double defaultVal) {
            return n.has(name) ? n.get(name).asDouble() : defaultVal;
        }

        public static float get(JsonNode n, String name, float defaultVal) {
            return n.has(name) ? (float)n.get(name).asDouble() : defaultVal;
        }

        public static String get(JsonNode n, String name, String defaultVal) {
            return n.has(name) ? n.get(name).asText() : defaultVal;
        }

    }
}

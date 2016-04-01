package com.graphics.render;

import com.graphics.geometry.Attribute;
import com.graphics.geometry.Mesh;
import com.graphics.shader.ShaderProgram;
import com.graphics.shader.Uniform;
import com.graphics.texture.Framebuffer;
import com.graphics.texture.Texture;
import com.joml.*;
import com.utils.Destroyable;
import org.lwjgl.opengl.GLUtil;

import java.nio.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * TODO - limit number of gl objects (reuse the least common ones or something)
 * TODO - Limit number of texture/mesh changes per frame
 *
 * Implementation of the renderer in OpenGL LWJGL
 *
 * Created by german on 26/03/2016.
 */
public class LwjglRenderer implements Renderer, Destroyable {

    private static boolean debug = true;

    private RendererListener listener = null;
    private RenderStats stats = new RenderStats();

    // gl state
    RenderMode renderMode = null;
    Blending blendMode = null;
    boolean blendEnabled = false;
    Culling cullMode = null;
    boolean cullEnabled = false;

    // texture handles
    private Map<Texture, Integer> textures = new HashMap<>();
    private Texture[] units;

    // mesh handles
    private Map<Mesh, Integer> vboPos = new HashMap<>();
    private Map<Mesh, Integer> vboColor = new HashMap<>();
    private Map<Mesh, Integer> vboUv = new HashMap<>();
    private Map<Mesh, Integer> ibos = new HashMap<>();

    // shader program handles
    private static FloatBuffer matbuffer = ByteBuffer.allocateDirect(16*1000<<2)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();
    private Map<ShaderProgram, Integer> programs = new HashMap<>();
    private Map<ShaderProgram, Integer> verts = new HashMap<>();
    private Map<ShaderProgram, Integer> frags = new HashMap<>();
    private ShaderProgram used = null;
    private int usedId = -1;

    // fbo handles
    private Map<Framebuffer, Integer> fbos = new HashMap<>();

    public LwjglRenderer() {
        int maxUnits = glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS);
        units = new Texture[maxUnits];

        System.err.println("GL_VENDOR: " + glGetString(GL_VENDOR));
        System.err.println("GL_RENDERER: " + glGetString(GL_RENDERER));
        System.err.println("GL_VERSION: " + glGetString(GL_VERSION));
        System.err.println();
    }

    @Override
    public void setListener(RendererListener listener) {
        this.listener = listener;
    }

    @Override
    public RendererListener getListener() {
        return listener;
    }

    @Override
    public RenderStats getStatistics() {
        return stats;
    }

    @Override
    public void beginFrame() {
        stats.reset();
        stats.allocatedEbos = ibos.size();
        stats.allocatedVbos = vboPos.size()+vboColor.size();
        stats.allocatedPrograms = programs.size();
        stats.allocatedFramebuffers = fbos.size();
        stats.allocatedTextures = textures.size();
        if (listener != null)
            listener.onRenderBegin(this);
    }

    @Override
    public void clearColor(float r, float g, float b, float a) {
        glClearColor(r, g, b, a);
    }

    @Override
    public void clearBuffers() {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
    }

    @Override
    public void clearColorBuffer() {
        glClear(GL_COLOR_BUFFER_BIT);
    }

    @Override
    public void clearDepthBuffer() {
        glClear(GL_DEPTH_BUFFER_BIT);
    }

    @Override
    public void viewport(int x, int y, int with, int height) {
        glViewport(x, y, with, height);
    }

    @Override
    public void setBlending(Blending blend) {
        if (blendMode == blend)
            return;

        blendMode = blend;
        switch (blend) {
            case Disabled:
                if (blendEnabled) {
                    blendEnabled = false;
                    glDisable(GL_BLEND);
                }
                break;
            case Alpha:
                if (!blendEnabled) {
                    blendEnabled = true;
                    glEnable(GL_BLEND);
                }
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                break;
            case Additive:
                if (!blendEnabled) {
                    blendEnabled = true;
                    glEnable(GL_BLEND);
                }
                glBlendFunc(GL_SRC_ALPHA, GL_ONE);
                break;
        }
    }

    @Override
    public void setDepth(boolean flag) {
        if (flag)
            glEnable(GL_DEPTH_TEST);
        else
            glDisable(GL_DEPTH_TEST);
    }

    @Override
    public void setColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        glColorMask(red, green, blue, alpha);
    }

    @Override
    public void setDepthMask(boolean depth) {
        glDepthMask(depth);
    }

    @Override
    public void setRenderMode(RenderMode mode) {
        if (mode != renderMode) {
            renderMode = mode;
            switch (mode) {
                default:    // error falls back to fill
                case Fill:
                    glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
                    break;
                case Wireframe:
                    glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
                    break;
            }
        }
    }

    @Override
    public void setCulling(Culling cull) {
        if (cull != cullMode) {
            cullMode = cull;
            switch (cull) {
                default:    // null disables
                case Disabled:
                    if (cullEnabled) {
                        glDisable(GL_CULL_FACE);
                        cullEnabled = false;
                    }
                    break;
                case BackFace:
                    if (!cullEnabled) {
                        glEnable(GL_CULL_FACE);
                        cullEnabled = true;
                    }
                    glCullFace(GL_BACK);
                case FrontFace:
                    if (!cullEnabled) {
                        glEnable(GL_CULL_FACE);
                        cullEnabled = true;
                    }
                    glCullFace(GL_FRONT);
                    break;
            }
        }
    }

    @Override
    public void setTexture(int unit, Texture texture) {
        if (units[unit] == texture && !texture.isDataDirty())
            return;

        Integer handle = textures.get(texture);
        if (handle == null) {
            handle = glGenTextures();
            textures.put(texture, handle);
            texture.setDataDirty(true);
        }

        // bind texture & upload to the gpu
        glActiveTexture(GL_TEXTURE0+unit);
        glBindTexture(GL_TEXTURE_2D, handle);
        stats.textureSwitch++;
        if (texture.isDataDirty()) {
            // reupload data to the gpu
            int format = LwjglUtils.TextureFormat2int(texture.getFormat());
            glTexImage2D(GL_TEXTURE_2D, 0, format, texture.getWidth(), texture.getHeight(), 0, format, GL_UNSIGNED_BYTE, texture.getData());
            glGenerateMipmap(GL_TEXTURE_2D);

            // we don't need the texture data anymore
            if (!texture.isKeepData()) {
                texture.getData().clear();
                texture.setData(null, true);    // do it silently
            }

            texture.setDataDirty(false);
        }

        if (texture.isParamsDirty()) {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, LwjglUtils.filter2int(texture.getMag()));
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, LwjglUtils.filter2int(texture.getMin()));
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, LwjglUtils.wrap2int(texture.getWrapU()));
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, LwjglUtils.wrap2int(texture.getWrapV()));
            texture.setParamsDirty(false);
        }

        units[unit] = texture;
    }

    @Override
    public void setShaderProgram(ShaderProgram program) {
        if (used == program) {
            // jerk move
            return;
        } else if (program == null) {
            glUseProgram(0);
            stats.shaderSwitch++;
            used = null;
            usedId = 0;
            return;
        }

        Integer handle = programs.get(program);
        if (handle == null) {
            // create shader
            int vert = LwjglUtils.getShader(GL_VERTEX_SHADER, program.getVertSource());
            int frag = LwjglUtils.getShader(GL_FRAGMENT_SHADER, program.getFragSource());
            handle = glCreateProgram();
            glAttachShader(handle, vert);
            glAttachShader(handle, frag);

            // set attributes
            Attribute[] attr = Attribute.values();
            for (int i = 0; i < attr.length; ++i)
                glBindAttribLocation(handle, attr[i].getId(), attr[i].getName());

            glLinkProgram(handle);

            programs.put(program, handle);
            verts.put(program, vert);
            frags.put(program, frag);
        }

        glUseProgram(handle);
        stats.shaderSwitch++;
        used = program;
        usedId = handle;
    }

    private static IntBuffer drawBuffers = ByteBuffer.allocateDirect(32<<2)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer();

    @Override
    public void setFramebuffer(Framebuffer fbo) {
        if (fbo == null) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            return;
        }

        Integer handle = fbos.get(fbo);
        if (handle == null) {
            fbo.setDirty(true);
            // create fbo
            handle = glGenFramebuffers();
            fbos.put(fbo, handle);
        }

        glBindFramebuffer(GL_FRAMEBUFFER, handle);
        stats.fboSwitch++;
        if (fbo.isDirty()) {
            // rebuild fbo and textures
            Texture[] targets = fbo.getTargets();
            for (int i = 0; i < targets.length; ++i) {
                setTexture(0, targets[i]);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0+i, GL_TEXTURE_2D, textures.get(targets[i]), 0);
            }

            if (fbo.hasDepth()) {
                Texture depth = fbo.getDepth();
                setTexture(0, depth);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, textures.get(depth), 0);
            }

            int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (status != GL_FRAMEBUFFER_COMPLETE)
                throw new RuntimeException("fbo not complete");

            fbo.setDirty(false);
        }

        // set render targets
        Texture[] targets = fbo.getTargets();
        for (int i = 0; i < targets.length; ++i)
            drawBuffers.put(GL_COLOR_ATTACHMENT0+i);

        drawBuffers.flip();
        glDrawBuffers(drawBuffers);
    }

    @Override
    public void renderMesh(Mesh mesh) {
        if (used == null)
            return;

        updateUniforms();

        Attribute[] attrs = used.getAttributes();
        for (int i = 0; i < attrs.length; ++i) {
            Buffer data = mesh.getData(attrs[i]);
            if (data == null) {
                // return? continue? fallback error model? I dunno :(
                //continue;
            }

            Integer vbo;
            switch (attrs[i]) {
                case Position:
                    vbo = vboPos.get(mesh);
                    if (vbo == null) {
                        vbo = glGenBuffers();
                        vboPos.put(mesh, vbo);
                        glBindBuffer(GL_ARRAY_BUFFER, vbo);
                        glBufferData(GL_ARRAY_BUFFER, (FloatBuffer) data, LwjglUtils.usage2int(mesh.getUsage()));
                        if (!mesh.isKeepData()) {
                            mesh.getData(attrs[i]).clear();
                            mesh.setData(attrs[i], null, true);
                        }
                    } else {
                        glBindBuffer(GL_ARRAY_BUFFER, vbo);
                        if (mesh.isDirty())
                            glBufferSubData(GL_ARRAY_BUFFER, data.position()<<2, (FloatBuffer) data);
                    }
                    break;
                case Uv:
                    vbo = vboUv.get(mesh);
                    if (vbo == null) {
                        vbo = glGenBuffers();
                        vboUv.put(mesh, vbo);
                        glBindBuffer(GL_ARRAY_BUFFER, vbo);
                        glBufferData(GL_ARRAY_BUFFER, (FloatBuffer) data, LwjglUtils.usage2int(mesh.getUsage()));
                        if (!mesh.isKeepData()) {
                            mesh.getData(attrs[i]).clear();
                            mesh.setData(attrs[i], null, true);
                        }
                    } else {
                        glBindBuffer(GL_ARRAY_BUFFER, vbo);
                        if (mesh.isDirty())
                            glBufferSubData(GL_ARRAY_BUFFER, data.position()<<2, (FloatBuffer) data);
                    }
                    break;
                case Color:
                    vbo = vboColor.get(mesh);
                    if (vbo == null) {
                        vbo = glGenBuffers();
                        vboColor.put(mesh, vbo);
                        glBindBuffer(GL_ARRAY_BUFFER, vbo);
                        glBufferData(GL_ARRAY_BUFFER, (FloatBuffer) data, LwjglUtils.usage2int(mesh.getUsage()));
                        if (!mesh.isKeepData()) {
                            mesh.getData(attrs[i]).clear();
                            mesh.setData(attrs[i], null, true);
                        }
                    } else {
                        glBindBuffer(GL_ARRAY_BUFFER, vbo);
                        if (mesh.isDirty())
                            glBufferSubData(GL_ARRAY_BUFFER, data.position()<<2, (FloatBuffer) data);
                    }
                    break;
            }

            // bind attribute
            glEnableVertexAttribArray(attrs[i].getId());
            glVertexAttribPointer(attrs[i].getId(), attrs[i].getSize(), GL_FLOAT, false, 0, 0);
            stats.vboCount++;
        }

        Integer ibo = ibos.get(mesh);
        if (ibo == null) {
            ibo = glGenBuffers();
            ibos.put(mesh, ibo);
            Buffer indices = mesh.getIndices();
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);

            if (indices instanceof IntBuffer)
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, (IntBuffer) indices, LwjglUtils.usage2int(mesh.getUsage()));
            else if (indices instanceof ShortBuffer)
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, (ShortBuffer) indices, LwjglUtils.usage2int(mesh.getUsage()));
            else if (indices instanceof ByteBuffer)
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, (ByteBuffer) indices, LwjglUtils.usage2int(mesh.getUsage()));
            else {
                // handle error? fallback to some error model? I dunno :(
            }
        } else {
            Buffer indices = mesh.getIndices();
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);

            if (mesh.isDirty()) {
                if (indices instanceof IntBuffer)
                    glBufferSubData(GL_ELEMENT_ARRAY_BUFFER, indices.position() << 2, (IntBuffer) indices);
                else if (indices instanceof ShortBuffer)
                    glBufferSubData(GL_ELEMENT_ARRAY_BUFFER, indices.position() << 1, (ShortBuffer) indices);
                else if (indices instanceof ByteBuffer)
                    glBufferSubData(GL_ELEMENT_ARRAY_BUFFER, indices.position(), (ByteBuffer) indices);
            }
        }

        mesh.setDirty(false);

        int offset = mesh.getOffset();
        int count = mesh.getLength();
        int prim = LwjglUtils.primitive2int(mesh.getPrimitive());

        if (mesh.getIndices() instanceof IntBuffer)
            glDrawElements(prim, count, GL_UNSIGNED_INT, offset << 2);
        else if (mesh.getIndices() instanceof ShortBuffer)
            glDrawElements(prim, count, GL_UNSIGNED_SHORT, offset<<1);
        else if (mesh.getIndices() instanceof ByteBuffer)
            glDrawElements(prim, count, GL_UNSIGNED_BYTE, offset);

        stats.vertices += count;

    }

    private void updateUniforms () {
        Set<Uniform> unif = used.getUniforms();
        unif.forEach(u -> {
            int loc = glGetUniformLocation(usedId, u.getName());
            switch (u.getType()) {
                case Float:
                    glUniform1f(loc, (float) u.getValue());
                    break;
                case Sampler2D:
                case Integer:
                    glUniform1i(loc, (int) u.getValue());
                    break;
                case Vector2:
                    Vector2f vec2 = (Vector2f) u.getValue();
                    glUniform2f(loc, vec2.x, vec2.y);
                    break;
                case Vector3:
                    Vector3f vec3 = (Vector3f) u.getValue();
                    glUniform3f(loc, vec3.x, vec3.y, vec3.z);
                    break;
                case Vector4:
                    Vector4f vec4 = (Vector4f) u.getValue();
                    glUniform4f(loc, vec4.x, vec4.y, vec4.z, vec4.w);
                    break;
                case Matrix3:
                    Matrix3f mat3 = (Matrix3f) u.getValue();
                    matbuffer.clear();
                    mat3.get(matbuffer).flip();
                    glUniformMatrix3fv(loc, false, matbuffer);
                    break;
                case Matrix3Array:
                    Matrix3f[] mat3arr = (Matrix3f[]) u.getValue();
                    matbuffer.clear();
                    for (int i = 0; i < mat3arr.length; ++i) {
                        mat3arr[i].get(matbuffer);
                        matbuffer.position(9*(i+1));
                    }
                    matbuffer.flip();
                    glUniformMatrix3fv(loc, false, matbuffer);
                    break;
                case Matrix4:
                    Matrix4f mat4 = (Matrix4f) u.getValue();
                    matbuffer.clear();
                    mat4.get(matbuffer).limit(16);
                    glUniformMatrix4fv(loc, false, matbuffer);
                    break;
                case Matrix4Array:
                    Matrix4f[] mat4arr = (Matrix4f[]) u.getValue();
                    matbuffer.clear();
                    for (int i = 0; i < mat4arr.length; ++i) {
                        mat4arr[i].get(matbuffer);
                        matbuffer.position(16*(i+1));
                    }
                    matbuffer.flip();
                    glUniformMatrix4fv(loc, false, matbuffer);
                    break;
            }
        });
    }

    @Override
    public void endFrame() {
        if (debug)
            GLUtil.checkGLError();

        if (listener != null)
            listener.onRenderEnd(this);
    }

    @Override
    public void destroy() {
        textures.forEach((tex, id) -> glDeleteTextures(id));

        vboPos.forEach((vbos, id) -> glDeleteBuffers(id));
        vboColor.forEach((vbos, id) -> glDeleteBuffers(id));
        vboUv.forEach((vbos, id) -> glDeleteBuffers(id));
        ibos.forEach((ibos, id) -> glDeleteBuffers(id));

        programs.forEach((prog, id) -> glDeleteProgram(id));
        verts.forEach((vert, id) -> glDeleteShader(id));
        frags.forEach((frag, id) -> glDeleteShader(id));

        fbos.forEach((fbo, id) -> glDeleteFramebuffers(id));
    }
}

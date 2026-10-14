package s3d.render;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import s3d.math.Mat4;

/** A linked GLSL 1.20 program with cached uniform locations. */
public final class Shader {
	private static final FloatBuffer MAT = BufferUtils.createFloatBuffer(16);

	public final String name;
	private final int program;
	private final Map<String, Integer> uniforms = new HashMap<>();

	private Shader(String name, int program) {
		this.name = name;
		this.program = program;
	}

	/** attribs[i] is bound to generic attribute location i (location 0 must be the position). */
	public static Shader build(String name, String vs, String fs, String... attribs) {
		int v = compile(name, GL20.GL_VERTEX_SHADER, vs);
		int f = compile(name, GL20.GL_FRAGMENT_SHADER, fs);
		int p = GL20.glCreateProgram();
		GL20.glAttachShader(p, v);
		GL20.glAttachShader(p, f);
		for (int i = 0; i < attribs.length; i++) GL20.glBindAttribLocation(p, i, attribs[i]);
		GL20.glLinkProgram(p);
		if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
			String logText = GL20.glGetProgramInfoLog(p, 4096);
			throw new IllegalStateException("Shader '" + name + "' link failed: " + logText);
		}
		GL20.glDetachShader(p, v);
		GL20.glDetachShader(p, f);
		GL20.glDeleteShader(v);
		GL20.glDeleteShader(f);
		return new Shader(name, p);
	}

	private static int compile(String name, int type, String src) {
		int s = GL20.glCreateShader(type);
		GL20.glShaderSource(s, src);
		GL20.glCompileShader(s);
		if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
			String logText = GL20.glGetShaderInfoLog(s, 4096);
			throw new IllegalStateException("Shader '" + name + "' " + (type == GL20.GL_VERTEX_SHADER ? "vertex" : "fragment")
					+ " compile failed: " + logText);
		}
		return s;
	}

	public Shader use() {
		GL20.glUseProgram(program);
		return this;
	}

	public int loc(String uniform) {
		Integer l = uniforms.get(uniform);
		if (l == null) {
			l = GL20.glGetUniformLocation(program, uniform);
			uniforms.put(uniform, l);
		}
		return l;
	}

	public Shader set(String u, float v) {
		GL20.glUniform1f(loc(u), v);
		return this;
	}

	public Shader set(String u, int v) {
		GL20.glUniform1i(loc(u), v);
		return this;
	}

	public Shader set(String u, float x, float y) {
		GL20.glUniform2f(loc(u), x, y);
		return this;
	}

	public Shader set(String u, float x, float y, float z) {
		GL20.glUniform3f(loc(u), x, y, z);
		return this;
	}

	public Shader set(String u, float x, float y, float z, float w) {
		GL20.glUniform4f(loc(u), x, y, z, w);
		return this;
	}

	/** Uniform float array (u is the array name). */
	public Shader setArray(String u, float[] v) {
		java.nio.FloatBuffer fb = org.lwjgl.BufferUtils.createFloatBuffer(v.length);
		fb.put(v).flip();
		GL20.glUniform1(loc(u), fb);
		return this;
	}

	public Shader set(String u, float[] v3) {
		return set(u, v3[0], v3[1], v3[2]);
	}

	public Shader set(String u, Mat4 m) {
		GL20.glUniformMatrix4(loc(u), false, m.store(MAT));
		return this;
	}

	public void delete() {
		GL20.glDeleteProgram(program);
	}
}

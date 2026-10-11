package s3d.render;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

/** Static interleaved float VBO + int index buffer, drawn with generic attributes. */
public final class Mesh {
	private final int vbo, ibo, indexCount, strideBytes;
	/** {location, componentCount, floatOffset} per attribute. */
	private final int[][] layout;

	private Mesh(int vbo, int ibo, int indexCount, int strideBytes, int[][] layout) {
		this.vbo = vbo;
		this.ibo = ibo;
		this.indexCount = indexCount;
		this.strideBytes = strideBytes;
		this.layout = layout;
	}

	public static Mesh upload(float[] verts, int[] indices, int floatsPerVertex, int[][] layout) {
		FloatBuffer vb = BufferUtils.createFloatBuffer(verts.length);
		vb.put(verts).flip();
		IntBuffer ib = BufferUtils.createIntBuffer(indices.length);
		ib.put(indices).flip();
		int vbo = GL15.glGenBuffers();
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vb, GL15.GL_STATIC_DRAW);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
		int ibo = GL15.glGenBuffers();
		GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ibo);
		GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, ib, GL15.GL_STATIC_DRAW);
		GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
		return new Mesh(vbo, ibo, indices.length, floatsPerVertex * 4, layout);
	}

	public void draw(int mode) {
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
		GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ibo);
		for (int[] a : layout) {
			GL20.glEnableVertexAttribArray(a[0]);
			GL20.glVertexAttribPointer(a[0], a[1], GL11.GL_FLOAT, false, strideBytes, a[2] * 4L);
		}
		GL11.glDrawElements(mode, indexCount, GL11.GL_UNSIGNED_INT, 0L);
		for (int[] a : layout) GL20.glDisableVertexAttribArray(a[0]);
		GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
	}

	public int indexCount() {
		return indexCount;
	}

	public void delete() {
		GL15.glDeleteBuffers(vbo);
		GL15.glDeleteBuffers(ibo);
	}
}

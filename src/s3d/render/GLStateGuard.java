package s3d.render;

import java.nio.FloatBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

/**
 * Saves the game's GL state before we draw and puts it back afterwards. The game renders
 * with fixed-function 2D state and doesn't expect anyone to touch programs, buffers or FBOs.
 * Matrices are saved by value (not glPushMatrix) so we can't overflow the game's matrix stacks.
 */
public final class GLStateGuard implements AutoCloseable {

	private static final FloatBuffer projM = BufferUtils.createFloatBuffer(16);
	private static final FloatBuffer modelM = BufferUtils.createFloatBuffer(16);
	private static final FloatBuffer texM = BufferUtils.createFloatBuffer(16);

	private int program, fbo, arrayBuf, elemBuf, activeTex, matrixMode;

	public static GLStateGuard push() {
		GLStateGuard g = new GLStateGuard();
		g.program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		g.fbo = GLCaps.fbo ? GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT) : 0;
		g.arrayBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
		g.elemBuf = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
		g.activeTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		g.matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);

		GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projM);
		GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelM);
		GL11.glGetFloat(GL11.GL_TEXTURE_MATRIX, texM);

		GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
		GL11.glPushClientAttrib(GL11.GL_ALL_CLIENT_ATTRIB_BITS);
		GL13.glActiveTexture(GL13.GL_TEXTURE0);

		// Neutral state for our passes.
		GL11.glDisable(GL11.GL_SCISSOR_TEST);
		GL11.glDisable(GL11.GL_STENCIL_TEST);
		GL11.glDisable(GL11.GL_ALPHA_TEST);
		GL11.glDisable(GL11.GL_LIGHTING);
		GL11.glDisable(GL11.GL_FOG);
		GL11.glColorMask(true, true, true, true);
		GL11.glMatrixMode(GL11.GL_TEXTURE);
		GL11.glLoadIdentity();
		GL11.glMatrixMode(GL11.GL_PROJECTION);
		GL11.glLoadIdentity();
		GL11.glMatrixMode(GL11.GL_MODELVIEW);
		GL11.glLoadIdentity();
		return g;
	}

	/** The framebuffer the game had bound; composite the scene back onto this. */
	public int previousFbo() {
		return fbo;
	}

	@Override
	public void close() {
		GL20.glUseProgram(program);
		if (GLCaps.fbo) EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, fbo);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuf);
		GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, elemBuf);

		GL11.glPopClientAttrib();
		GL11.glPopAttrib();

		GL11.glMatrixMode(GL11.GL_TEXTURE);
		GL11.glLoadMatrix(texM);
		GL11.glMatrixMode(GL11.GL_PROJECTION);
		GL11.glLoadMatrix(projM);
		GL11.glMatrixMode(GL11.GL_MODELVIEW);
		GL11.glLoadMatrix(modelM);
		GL11.glMatrixMode(matrixMode);
		GL13.glActiveTexture(activeTex);
	}
}

package s3d.render;

import static org.lwjgl.opengl.EXTFramebufferObject.*;

import java.nio.ByteBuffer;

import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;

import com.fs.starfarer.api.Global;

/**
 * Low-resolution colour + depth textures for the retro filter's ship pass. Ships render here at
 * a fraction of the screen size, then are composited into the scene target with their depth, so
 * the effects drawn afterwards still sort against them. No MSAA: hard pixel edges are the look.
 */
final class RetroTarget {
	private static final Logger log = Global.getLogger(RetroTarget.class);

	private int w, h, fbo, colorTex, depthTex;
	private boolean failed;

	/** (Re)creates the textures when the size changes. Returns false if the target can't be made. */
	boolean ensure(int width, int height) {
		if (failed) return false;
		if (width == w && height == h && fbo != 0) return true;
		release();
		w = width;
		h = height;
		colorTex = texture(GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
		depthTex = texture(GL14.GL_DEPTH_COMPONENT24, GL11.GL_DEPTH_COMPONENT, GL11.GL_UNSIGNED_INT);
		fbo = glGenFramebuffersEXT();
		glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);
		glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT, GL_COLOR_ATTACHMENT0_EXT, GL11.GL_TEXTURE_2D, colorTex, 0);
		glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT, GL_DEPTH_ATTACHMENT_EXT, GL11.GL_TEXTURE_2D, depthTex, 0);
		int status = glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT);
		if (status != GL_FRAMEBUFFER_COMPLETE_EXT) {
			log.error("S3D: retro FBO incomplete (status 0x" + Integer.toHexString(status) + "), retro filter disabled");
			release();
			failed = true;
			return false;
		}
		log.info("S3D: retro target " + w + "x" + h);
		return true;
	}

	private int texture(int internal, int format, int type) {
		int tex = GL11.glGenTextures();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, (ByteBuffer) null);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
		return tex;
	}

	/** Binds the target, sets the viewport and clears to transparent / far. */
	void begin() {
		glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);
		GL11.glViewport(0, 0, w, h);
		GL11.glClearColor(0f, 0f, 0f, 0f);
		GL11.glClearDepth(1.0);
		GL11.glDepthMask(true);
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
	}

	int colorTexture() {
		return colorTex;
	}

	int depthTexture() {
		return depthTex;
	}

	int width() {
		return w;
	}

	int height() {
		return h;
	}

	void release() {
		if (fbo != 0) glDeleteFramebuffersEXT(fbo);
		if (colorTex != 0) GL11.glDeleteTextures(colorTex);
		if (depthTex != 0) GL11.glDeleteTextures(depthTex);
		fbo = colorTex = depthTex = 0;
		w = h = 0;
	}
}

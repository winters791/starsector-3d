package s3d.render;

import static org.lwjgl.opengl.EXTFramebufferObject.*;

import java.nio.ByteBuffer;

import org.apache.log4j.Logger;
import org.lwjgl.opengl.EXTFramebufferBlit;
import org.lwjgl.opengl.EXTFramebufferMultisample;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;

import com.fs.starfarer.api.Global;

/**
 * Our own colour+depth render target. We never rely on the game's framebuffer having a depth
 * buffer: the 3D scene renders here (optionally multisampled), resolves into a texture, and
 * that texture is composited onto whatever framebuffer the game had bound.
 */
public final class SceneTarget {
	private static final Logger log = Global.getLogger(SceneTarget.class);

	private int w, h, samples = -1;
	private int fbo, colorTex, depthRb;
	private int msFbo, msColorRb, msDepthRb;

	/** (Re)creates buffers when size/sample count change. Returns false if FBOs are unusable. */
	public boolean ensure(int width, int height, int wantSamples) {
		int s = GLCaps.msaa ? Math.min(Math.max(0, wantSamples), GLCaps.maxSamples) : 0;
		if (width == w && height == h && s == samples && fbo != 0) return true;
		release();
		w = width;
		h = height;

		colorTex = GL11.glGenTextures();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

		fbo = glGenFramebuffersEXT();
		glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);
		glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT, GL_COLOR_ATTACHMENT0_EXT, GL11.GL_TEXTURE_2D, colorTex, 0);

		if (s > 0 && createMultisample(s)) {
			samples = s;
		} else {
			samples = 0;
			glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);
			depthRb = glGenRenderbuffersEXT();
			glBindRenderbufferEXT(GL_RENDERBUFFER_EXT, depthRb);
			glRenderbufferStorageEXT(GL_RENDERBUFFER_EXT, GL14.GL_DEPTH_COMPONENT24, w, h);
			glFramebufferRenderbufferEXT(GL_FRAMEBUFFER_EXT, GL_DEPTH_ATTACHMENT_EXT, GL_RENDERBUFFER_EXT, depthRb);
		}
		glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);
		int status = glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT);
		glBindRenderbufferEXT(GL_RENDERBUFFER_EXT, 0);
		if (status != GL_FRAMEBUFFER_COMPLETE_EXT) {
			log.error("S3D: scene FBO incomplete (status 0x" + Integer.toHexString(status) + ")");
			release();
			return false;
		}
		log.info("S3D: scene target " + w + "x" + h + " msaa=" + samples);
		return true;
	}

	private boolean createMultisample(int s) {
		msFbo = glGenFramebuffersEXT();
		glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, msFbo);
		msColorRb = glGenRenderbuffersEXT();
		glBindRenderbufferEXT(GL_RENDERBUFFER_EXT, msColorRb);
		EXTFramebufferMultisample.glRenderbufferStorageMultisampleEXT(GL_RENDERBUFFER_EXT, s, GL11.GL_RGBA8, w, h);
		glFramebufferRenderbufferEXT(GL_FRAMEBUFFER_EXT, GL_COLOR_ATTACHMENT0_EXT, GL_RENDERBUFFER_EXT, msColorRb);
		msDepthRb = glGenRenderbuffersEXT();
		glBindRenderbufferEXT(GL_RENDERBUFFER_EXT, msDepthRb);
		EXTFramebufferMultisample.glRenderbufferStorageMultisampleEXT(GL_RENDERBUFFER_EXT, s, GL14.GL_DEPTH_COMPONENT24, w, h);
		glFramebufferRenderbufferEXT(GL_FRAMEBUFFER_EXT, GL_DEPTH_ATTACHMENT_EXT, GL_RENDERBUFFER_EXT, msDepthRb);
		int status = glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT);
		if (status != GL_FRAMEBUFFER_COMPLETE_EXT) {
			log.warn("S3D: MSAA x" + s + " unavailable (status 0x" + Integer.toHexString(status) + "), falling back to no MSAA");
			deleteMultisample();
			return false;
		}
		return true;
	}

	/** Binds the target for drawing and sets the viewport. */
	public void begin() {
		glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, samples > 0 ? msFbo : fbo);
		GL11.glViewport(0, 0, w, h);
	}

	/** Resolves MSAA into the texture. Leaves the resolve FBO bound. */
	public void resolve() {
		if (samples > 0) {
			glBindFramebufferEXT(EXTFramebufferBlit.GL_READ_FRAMEBUFFER_EXT, msFbo);
			glBindFramebufferEXT(EXTFramebufferBlit.GL_DRAW_FRAMEBUFFER_EXT, fbo);
			EXTFramebufferBlit.glBlitFramebufferEXT(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
		}
		glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);
	}

	public int texture() {
		return colorTex;
	}

	public int samples() {
		return samples;
	}

	public int width() {
		return w;
	}

	public int height() {
		return h;
	}

	private void deleteMultisample() {
		if (msFbo != 0) glDeleteFramebuffersEXT(msFbo);
		if (msColorRb != 0) glDeleteRenderbuffersEXT(msColorRb);
		if (msDepthRb != 0) glDeleteRenderbuffersEXT(msDepthRb);
		msFbo = msColorRb = msDepthRb = 0;
	}

	public void release() {
		deleteMultisample();
		if (fbo != 0) glDeleteFramebuffersEXT(fbo);
		if (depthRb != 0) glDeleteRenderbuffersEXT(depthRb);
		if (colorTex != 0) GL11.glDeleteTextures(colorTex);
		fbo = depthRb = colorTex = 0;
		w = h = 0;
		samples = -1;
	}
}

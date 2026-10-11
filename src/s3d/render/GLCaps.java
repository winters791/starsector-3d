package s3d.render;

import org.apache.log4j.Logger;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.EXTFramebufferMultisample;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

import com.fs.starfarer.api.Global;

/** What the game's OpenGL context can do. Detected once, logged once. */
public final class GLCaps {
	private static final Logger log = Global.getLogger(GLCaps.class);

	public static boolean detected;
	public static String version = "?", renderer = "?", vendor = "?", glsl = "?";
	public static boolean shaders, fbo, msaa, blit, aniso;
	public static int maxSamples, depthBitsDefault;
	public static float maxAniso;

	private GLCaps() {}

	public static void detect() {
		if (detected) return;
		ContextCapabilities c = GLContext.getCapabilities();
		version = GL11.glGetString(GL11.GL_VERSION);
		renderer = GL11.glGetString(GL11.GL_RENDERER);
		vendor = GL11.glGetString(GL11.GL_VENDOR);
		shaders = c.OpenGL20;
		if (shaders) glsl = GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION);
		fbo = c.GL_EXT_framebuffer_object;
		msaa = fbo && c.GL_EXT_framebuffer_multisample && c.GL_EXT_framebuffer_blit;
		blit = c.GL_EXT_framebuffer_blit;
		aniso = c.GL_EXT_texture_filter_anisotropic;
		maxSamples = msaa ? GL11.glGetInteger(EXTFramebufferMultisample.GL_MAX_SAMPLES_EXT) : 0;
		maxAniso = aniso ? GL11.glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT) : 1f;
		depthBitsDefault = GL11.glGetInteger(GL11.GL_DEPTH_BITS);
		while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* drain */ }
		detected = true;
		log.info("S3D GL: " + summary());
	}

	public static String summary() {
		return "GL " + version + " | GLSL " + glsl + " | " + renderer + " (" + vendor + ")"
				+ " | fbo=" + fbo + " msaa=" + msaa + " maxSamples=" + maxSamples
				+ " aniso=" + maxAniso + " depthBits(current fb)=" + depthBitsDefault;
	}
}

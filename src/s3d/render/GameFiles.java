package s3d.render;

import java.awt.image.BufferedImage;
import java.io.InputStream;

import javax.imageio.ImageIO;

/**
 * The game's own files by path ("graphics/fonts/victor14.fnt", weapon and status icon sprites).
 * The host supplies the opener (the game's resource loader, mods included); the preview tools read
 * the install. Null opener: nothing can be loaded and callers fall back.
 */
public final class GameFiles {
	public interface Opener {
		InputStream open(String path) throws Exception;
	}

	public static Opener opener;

	private GameFiles() {}

	static InputStream open(String path) throws Exception {
		if (opener == null) throw new IllegalStateException("no game file opener set");
		return opener.open(path);
	}

	static BufferedImage image(String path) throws Exception {
		try (InputStream in = open(path)) {
			BufferedImage img = ImageIO.read(in);
			if (img == null) throw new IllegalStateException("could not decode " + path);
			return img;
		}
	}
}

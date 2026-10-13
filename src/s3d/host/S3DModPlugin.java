package s3d.host;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

public class S3DModPlugin extends BaseModPlugin {
	public static final String MOD_ID = "starsector3d";
	public static final String VERSION = "0.1.0";
	private static final Logger log = Global.getLogger(S3DModPlugin.class);

	@Override
	public void onApplicationLoad() {
		BattleLog.install(); // first, so the lines below go into the bug-report files too
		log.info("S3D: Starsector 3D " + VERSION + " loaded (java " + System.getProperty("java.version")
				+ ", " + System.getProperty("os.arch") + ")");
		CompatReport.write(); // also reads the mods' data/config/s3d files
	}
}

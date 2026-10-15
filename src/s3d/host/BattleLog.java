package s3d.host;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;

import s3d.render.GLCaps;

/**
 * The bug-report files. Keeps this mod's own log lines (every s3d.* logger) for the current 3D
 * battle, plus warnings and errors from anything else while it runs, and writes them to
 * saves/common with what a report needs at the top (versions, OS, Java, graphics, mods, ships):
 * s3d_last_battle.txt (refreshed every minute, on an error and when the battle ends),
 * s3d_previous_battle.txt (the one before, kept when a new 3D battle starts) and
 * s3d_last_error.txt (written on an error in our code, so later battles don't overwrite it).
 * starsector.log keeps everything as before; this is the part of it a tester can send.
 */
final class BattleLog extends AppenderSkeleton {
	private static final Logger log = Global.getLogger(BattleLog.class);
	static final String BATTLE_FILE = "s3d_last_battle.txt", PREVIOUS_FILE = "s3d_previous_battle.txt", ERROR_FILE = "s3d_last_error.txt";
	private static final BattleLog INSTANCE = new BattleLog();
	/** writeTextFileToCommon takes at most 1 MB: the battle's start, its latest lines and the problems fit in this. */
	private static final int HEAD_CHARS = 250_000, TAIL_CHARS = 400_000, MAX_CHARS = 950_000;
	private static final int MAX_PROBLEMS = 60, MAX_TRACE_LINES = 40, MAX_OUTSIDE = 200;
	private static final long FLUSH_NS = 60_000_000_000L, SHIPS_NS = 10_000_000_000L;
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

	/** Lines from outside 3D battles (game start: version, compatibility report, data-file problems; 2D battles). */
	private final List<String> outside = new ArrayList<>();
	private int outsideDropped;
	/** The battle's lines: the first HEAD_CHARS kept, then a rolling window of the latest TAIL_CHARS. */
	private final List<String> head = new ArrayList<>();
	private final ArrayDeque<String> tail = new ArrayDeque<>();
	private int headChars, tailChars, dropped;
	/** Warnings and errors, also listed at the top. */
	private final List<String> problems = new ArrayList<>();
	private int problemsDropped;
	private boolean active, installed;
	private String kind, outcome, ships = "", mods = "", lastText;
	private long startMs, lastWriteNs, lastShipsNs;
	private CombatEngineAPI engine;
	/** The thread writing a file: what the game logs while it writes isn't taken in (no loop). */
	private volatile Thread writer;

	private BattleLog() {
		setName("s3d-battle-log");
	}

	/** At game start: from now on, every log line passes through here. */
	static void install() {
		BattleLog b = INSTANCE;
		if (b.installed) return;
		StringBuilder m = new StringBuilder();
		try {
			for (ModSpecAPI s : Global.getSettings().getModManager().getEnabledModsCopy()) {
				if (m.length() > 0) m.append(", ");
				m.append(s.getId()).append(' ').append(s.getVersion());
			}
		} catch (Throwable t) {
			m.append("? (").append(t).append(')');
		}
		b.mods = m.toString();
		try {
			// The last session's last battle becomes the previous battle when the first 3D battle starts.
			String last = Global.getSettings().readTextFileFromCommon(BATTLE_FILE);
			if (last != null && !last.isEmpty()) b.lastText = last;
		} catch (Throwable none) {
			// first run
		}
		Logger.getRootLogger().addAppender(b);
		b.installed = true;
	}

	/**
	 * A battle starts (kind null = a vanilla 2D one). Closes the last 3D battle's file, keeps it as
	 * the previous battle when another 3D battle starts, and starts collecting for this one.
	 */
	static void battleInit(CombatEngineAPI engine, String kind) {
		BattleLog b = INSTANCE;
		if (!b.installed) return;
		String previous = null;
		boolean close;
		synchronized (b) {
			close = b.active;
			if (close && b.outcome == null) b.outcome = "ended (the game closed the battle)";
		}
		if (close) b.flush(false);
		synchronized (b) {
			b.active = false;
			if (kind != null) {
				previous = b.lastText;
				b.lastText = null;
				b.head.clear();
				b.tail.clear();
				b.problems.clear();
				b.headChars = b.tailChars = b.dropped = b.problemsDropped = 0;
				b.kind = kind;
				b.outcome = null;
				b.ships = "";
				b.engine = engine;
				b.startMs = System.currentTimeMillis();
				b.lastWriteNs = b.lastShipsNs = System.nanoTime();
				b.active = true;
			} else {
				b.engine = null;
			}
		}
		if (previous != null) b.write(PREVIOUS_FILE, previous);
	}

	/** Every frame of a 3D battle: refreshes the ship list now and then, and the file every minute. */
	static void tick() {
		BattleLog b = INSTANCE;
		if (!b.active) return;
		long now = System.nanoTime();
		if (now - b.lastShipsNs > SHIPS_NS) b.refreshShips();
		if (now - b.lastWriteNs > FLUSH_NS) b.flush(false);
	}

	/** The battle reached an end in 3D (handed back, stayed 2D, over): says so and writes the file. */
	static void end(String how) {
		BattleLog b = INSTANCE;
		synchronized (b) {
			if (!b.active || b.outcome != null) return;
			b.outcome = how;
		}
		b.refreshShips();
		b.flush(false);
	}

	/** The 3D layer is about to report an error and switch off: what happened goes at the top of the files. */
	static void failing(String how) {
		BattleLog b = INSTANCE;
		synchronized (b) {
			b.outcome = how;
		}
		b.refreshShips();
	}

	/**
	 * Inside append on the thread holding doAppend's lock. Some game builds' script class loader logs every
	 * class it loads, and this mod's jar comes from that loader: the classes append touches the first time
	 * log a line that comes straight back here. Checked before anything else (no class to load: own field).
	 */
	private boolean inside;

	@Override
	protected void append(LoggingEvent e) {
		if (inside) return;
		inside = true;
		try {
			take(e);
		} finally {
			inside = false;
		}
	}

	private void take(LoggingEvent e) {
		if (Thread.currentThread() == writer) return;
		String name = e.getLoggerName() != null ? e.getLoggerName() : "?";
		boolean ours = name.startsWith("s3d.");
		Level level = e.getLevel();
		boolean warn = level != null && level.isGreaterOrEqual(Level.WARN);
		String[] trace = e.getThrowableStrRep();
		// An error in our code: logged by us, or by the game after something of ours threw.
		boolean ourError = level != null && level.isGreaterOrEqual(Level.ERROR) && trace != null && (ours || mentionsUs(trace));
		synchronized (this) {
			if (active ? !ours && !warn : !ours && !ourError) return;
			String line = format(e, name, ours, trace);
			if (active) {
				addLine(line);
			} else if (outside.size() < MAX_OUTSIDE) {
				outside.add(line);
			} else {
				outsideDropped++;
			}
			if (warn) {
				if (problems.size() < MAX_PROBLEMS) problems.add(line);
				else problemsDropped++;
			}
		}
		if (ourError) flush(true);
	}

	private static boolean mentionsUs(String[] trace) {
		for (String l : trace) if (l.contains("at s3d.")) return true;
		return false;
	}

	private String format(LoggingEvent e, String name, boolean ours, String[] trace) {
		String msg = e.getRenderedMessage() != null ? e.getRenderedMessage() : "";
		if (msg.startsWith("S3D: ")) msg = msg.substring(5);
		StringBuilder b = new StringBuilder();
		b.append(CLOCK.format(Instant.ofEpochMilli(e.timeStamp))).append(' ').append(String.format("%-5s", e.getLevel())).append(' ');
		if (!ours) b.append('[').append(name.substring(name.lastIndexOf('.') + 1)).append("] ");
		b.append(msg);
		if (trace != null) {
			int n = Math.min(trace.length, MAX_TRACE_LINES);
			for (int i = 0; i < n; i++) b.append("\n        ").append(trace[i].replace("\t", ""));
			if (trace.length > n) b.append("\n        ... ").append(trace.length - n).append(" more lines");
		}
		return b.toString();
	}

	private void addLine(String line) {
		if (headChars + line.length() < HEAD_CHARS && tail.isEmpty()) {
			head.add(line);
			headChars += line.length() + 1;
			return;
		}
		tail.addLast(line);
		tailChars += line.length() + 1;
		while (tailChars > TAIL_CHARS && tail.size() > 1) {
			tailChars -= tail.removeFirst().length() + 1;
			dropped++;
		}
	}

	/** The ships on the field, by side. Only from the game thread (the engine isn't thread-safe). */
	private void refreshShips() {
		CombatEngineAPI eng = engine;
		if (eng == null) return;
		StringBuilder[] side = {new StringBuilder(), new StringBuilder(), new StringBuilder()};
		int[] fighters = new int[3];
		try {
			for (ShipAPI s : eng.getShips()) {
				int o = s.getOwner() == 0 ? 0 : s.getOwner() == 1 ? 1 : 2;
				if (s.isFighter()) {
					fighters[o]++;
					continue;
				}
				StringBuilder b = side[o];
				if (b.length() > 0) b.append(", ");
				b.append(s.getHullSpec() != null ? s.getHullSpec().getHullId() : "?");
				if (s.getVariant() != null) b.append(" (").append(s.getVariant().getHullVariantId()).append(')');
				if (s.getName() != null && !s.getName().isEmpty()) b.append(" \"").append(s.getName()).append('"');
				if (s.isHulk() || !s.isAlive()) b.append(" [destroyed]");
				else if (s.isRetreating()) b.append(" [retreating]");
			}
		} catch (Throwable t) {
			return; // keep the last list
		}
		String[] names = {"Yours", "Enemy", "Other"};
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < 3; i++) {
			if (side[i].length() == 0 && fighters[i] == 0) continue;
			out.append(names[i]).append(": ").append(side[i]).append(fighters[i] > 0 ? (side[i].length() > 0 ? "; " : "") + fighters[i] + " fighters" : "").append('\n');
		}
		synchronized (this) {
			ships = out.toString();
			lastShipsNs = System.nanoTime();
		}
	}

	private void flush(boolean error) {
		String text;
		synchronized (this) {
			text = text(error);
			lastText = text;
			lastWriteNs = System.nanoTime();
		}
		write(BATTLE_FILE, text);
		if (error) write(ERROR_FILE, text);
	}

	private String text(boolean error) {
		StringBuilder b = new StringBuilder(headChars + tailChars + 20_000);
		long now = System.currentTimeMillis();
		b.append("Starsector 3D bug-report file, written ").append(STAMP.format(Instant.ofEpochMilli(now)))
				.append(error ? " after an error" : "").append(".\n");
		b.append("With a bug report, please send every s3d_*.txt.data file in saves/common: s3d_last_battle, s3d_previous_battle,\n");
		b.append("s3d_last_error if there is one, and s3d_compatibility if you play with other mods.\n\n");
		if (kind != null) {
			b.append("Battle: ").append(kind).append(", started ").append(STAMP.format(Instant.ofEpochMilli(startMs)))
					.append(String.format(" (%.0f s ago), %s", (now - startMs) / 1000f, outcome != null ? outcome : "still running")).append('\n');
		} else {
			b.append("Battle: none in 3D yet\n");
		}
		b.append("Starsector 3D ").append(S3DModPlugin.VERSION).append(", game ").append(Global.getSettings().getVersionString()).append('\n');
		b.append("System: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append(' ')
				.append(System.getProperty("os.arch")).append(", Java ").append(System.getProperty("java.version")).append(" (")
				.append(System.getProperty("java.vendor")).append("), heap ").append(Runtime.getRuntime().maxMemory() / (1024 * 1024)).append(" MB\n");
		b.append("Graphics: ").append(GLCaps.detected ? GLCaps.summary() : "not checked yet (no 3D view opened)").append('\n');
		b.append(String.format("Screen: %.0f x %.0f px, UI scale %.2f%n", Global.getSettings().getScreenWidthPixels(), Global.getSettings().getScreenHeightPixels(),
				Global.getSettings().getScreenScaleMult()));
		b.append("Mods: ").append(mods).append('\n');
		if (!ships.isEmpty()) b.append(ships);

		b.append("\n== Warnings and errors (").append(problems.size() + problemsDropped).append(") ==\n");
		if (problems.isEmpty()) b.append("none\n");
		for (String p : problems) b.append(p).append('\n');
		if (problemsDropped > 0) b.append("... ").append(problemsDropped).append(" more (in the battle log below)\n");

		b.append("\n== Outside 3D battles (game start, 2D battles) ==\n");
		for (String l : outside) b.append(l).append('\n');
		if (outsideDropped > 0) b.append("... ").append(outsideDropped).append(" more lines left out\n");

		if (kind != null) {
			b.append("\n== This battle ==\n");
			for (String l : head) b.append(l).append('\n');
			if (dropped > 0) b.append("\n... ").append(dropped).append(" lines left out (the file can be at most 1 MB) ...\n\n");
			for (String l : tail) b.append(l).append('\n');
		}
		if (b.length() > MAX_CHARS) {
			b.setLength(MAX_CHARS);
			b.append("\n... (cut here: the file can be at most 1 MB)\n");
		}
		return b.toString();
	}

	private void write(String file, String text) {
		writer = Thread.currentThread();
		try {
			Global.getSettings().writeTextFileToCommon(file, text);
		} catch (Throwable t) {
			log.warn("S3D: couldn't write saves/common/" + file + ".data", t); // not taken in: this thread is the writer
		} finally {
			writer = null;
		}
	}

	@Override
	public boolean requiresLayout() {
		return false;
	}

	@Override
	public void close() {
		closed = true;
	}
}

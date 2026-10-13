package s3d.host;

import java.awt.Color;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.ui.LabelAPI;

import s3d.render.HudRenderer;

/**
 * Vanilla's status list for your ship (Command Network, ECM rating, Coordinated Maneuvers, peak
 * time, combat readiness, ion storms, skills, systems...): everything any script reports with
 * CombatEngineAPI.maintainStatusForPlayerShip, plus the entries vanilla adds itself. The API can
 * only write that list, so it's read back from vanilla's ship panel (kept running, just faded out,
 * while the 3D view is up): CombatState.getShipInfo().getStatusList(), its list of entries, and in
 * each entry the icon (getSpriteName) and the two text lines (LabelAPI: title, then data, red =
 * debuff), fading as vanilla fades them.
 *
 * The game's script class loader refuses java.lang.reflect to mods, so fields and methods are held
 * as plain Objects and used through java.lang.invoke handles. Only public method names and public
 * interfaces are relied on, never obfuscated names. Anything unexpected: logged once, and the 3D
 * HUD simply shows no list.
 */
final class VanillaStatus {
	private static final Logger log = Global.getLogger(VanillaStatus.class);
	private static final int STATIC = 0x0008, SYNTHETIC = 0x1000; // java.lang.reflect.Modifier's bits
	private static boolean failed, loggedFirst;
	private static MethodHandle declaredFields, fieldGet, fieldType, fieldModifiers, fieldAccessible, getMethod, invoke;
	private static final Map<Class<?>, Object[]> FIELDS = new HashMap<>();
	private static final Map<String, Object> METHODS = new HashMap<>();
	private static final Object NO_METHOD = new Object();

	private VanillaStatus() {}

	private static void init() throws Throwable {
		if (declaredFields != null) return;
		MethodHandles.Lookup l = MethodHandles.lookup();
		Class<?> field = Class.forName("java.lang.reflect.Field", false, null);
		Class<?> method = Class.forName("java.lang.reflect.Method", false, null);
		Class<?> fields = Class.forName("[Ljava.lang.reflect.Field;", false, null);
		declaredFields = l.findVirtual(Class.class, "getDeclaredFields", MethodType.methodType(fields));
		fieldGet = l.findVirtual(field, "get", MethodType.methodType(Object.class, Object.class));
		fieldType = l.findVirtual(field, "getType", MethodType.methodType(Class.class));
		fieldModifiers = l.findVirtual(field, "getModifiers", MethodType.methodType(int.class));
		fieldAccessible = l.findVirtual(field, "setAccessible", MethodType.methodType(void.class, boolean.class));
		getMethod = l.findVirtual(Class.class, "getMethod", MethodType.methodType(method, String.class, Class[].class));
		invoke = l.findVirtual(method, "invoke", MethodType.methodType(Object.class, Object.class, Object[].class));
	}

	/** Fills out with the current entries, bottom of vanilla's list first. */
	static void read(CombatEngineAPI engine, List<HudRenderer.Status> out) {
		out.clear();
		if (failed || engine == null) return;
		try {
			init();
			Object ui = engine.getCombatUI();
			Object info = ui != null ? call(ui, "getShipInfo") : null;
			Object list = info != null ? call(info, "getStatusList") : null;
			if (list == null) return;
			List<?> entries = shownEntries(list);
			for (Object e : entries) {
				String icon = null;
				LabelAPI title = null, data = null;
				for (Object f : fields(e.getClass())) {
					Object v = fieldGet.invoke(f, e);
					if (v == null) continue;
					if (v instanceof LabelAPI) {
						if (title == null) title = (LabelAPI) v;
						else if (data == null) data = (LabelAPI) v;
					} else if (icon == null) {
						Object s = call(v, "getSpriteName");
						if (s instanceof String) icon = (String) s;
					}
				}
				if (title == null) continue;
				HudRenderer.Status s = new HudRenderer.Status();
				s.icon = icon;
				s.title = title.getText();
				s.data = data != null ? data.getText() : null;
				s.debuff = data != null && reddish(data.getColor());
				Object fader = call(e, "getFader");
				Object b = fader != null ? call(fader, "getBrightness") : null;
				s.alpha = b instanceof Float ? (Float) b : 1f;
				out.add(s);
			}
			if (!loggedFirst && !out.isEmpty()) {
				loggedFirst = true;
				HudRenderer.Status s = out.get(0);
				log.info(String.format("S3D: reading vanilla's status list: %d entries, first \"%s\" / \"%s\"%s, icon %s",
						out.size(), s.title, s.data, s.debuff ? " (debuff)" : "", s.icon));
			}
		} catch (Throwable t) {
			failed = true;
			out.clear();
			log.warn("S3D: can't read vanilla's status list (ECM, peak time...); the 3D HUD leaves it out", t);
		}
	}

	/** The status widget's first List field: its entries in display order (the second is a per-frame scratch list). */
	private static List<?> shownEntries(Object list) throws Throwable {
		for (Object f : fields(list.getClass())) {
			if (!List.class.isAssignableFrom((Class<?>) fieldType.invoke(f))) continue;
			Object v = fieldGet.invoke(f, list);
			if (v instanceof List) return (List<?>) v;
		}
		throw new IllegalStateException("status widget " + list.getClass().getName() + " has no list of entries");
	}

	private static boolean reddish(Color c) {
		return c != null && c.getRed() > 150 && c.getRed() > 1.4f * Math.max(c.getGreen(), c.getBlue());
	}

	/** The class's own instance fields (as Objects), made accessible, in declaration order; cached. */
	private static Object[] fields(Class<?> c) throws Throwable {
		Object[] fs = FIELDS.get(c);
		if (fs != null) return fs;
		List<Object> keep = new ArrayList<>();
		for (Object f : (Object[]) declaredFields.invoke(c)) {
			int mods = (int) fieldModifiers.invoke(f);
			if ((mods & (STATIC | SYNTHETIC)) != 0) continue;
			fieldAccessible.invoke(f, true);
			keep.add(f);
		}
		fs = keep.toArray();
		FIELDS.put(c, fs);
		return fs;
	}

	/** A public no-argument method by name (cached per class), called; null if the class has none. */
	private static Object call(Object o, String name) throws Throwable {
		String key = o.getClass().getName() + "#" + name;
		Object m = METHODS.get(key);
		if (m == null) {
			try {
				m = getMethod.invoke(o.getClass(), name, new Class<?>[0]);
			} catch (NoSuchMethodException e) {
				m = NO_METHOD;
			}
			METHODS.put(key, m);
		}
		return m == NO_METHOD ? null : invoke.invoke(m, o, new Object[0]);
	}
}

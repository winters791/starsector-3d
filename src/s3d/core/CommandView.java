package s3d.core;

import java.util.ArrayList;
import java.util.List;

import s3d.math.Vec3;

/**
 * Tactical view (T) as a command view: a free camera over the battle, your ships picked with the
 * mouse and orders given by right click. Your own ship flies itself meanwhile (Sim's autopilot).
 *
 * Camera: W/S/A/D pan along the ground, Q/E turn, wheel zooms, hold L-Alt or the middle button and
 * move the mouse to orbit. The view follows a ship (yours at first) until you pan; double click a
 * ship to follow it.
 *
 * Mouse: left click selects one of your ships (Shift adds/removes), left drag selects a box, left
 * click on nothing clears. Right click gives the obvious order for what's under the cursor (enemy:
 * engage, your ship: escort, objective: capture, empty space: move there). Right press on empty
 * space and drag up or down (or turn the wheel) to set the height of the point, at any time while
 * the button is down. Press and hold the right button still, then let go: a ring of the other orders
 * (avoid, cancel, retreat) stays open; left click one (anywhere else closes it).
 *
 * Two buttons bottom right: full assault (on/off, vanilla's) and full retreat (click twice; it can't
 * be undone).
 *
 * Orders come out as {@link Command}s in {@link #issued}; the host turns them into vanilla's own
 * assignments (command points and all), which come back to the pilots as their {@link Order3D}.
 * Nothing selected = an order for the fleet (vanilla's admiral picks who goes).
 */
public final class CommandView {

	public enum Kind {
		/** Attack an enemy ship. */
		ENGAGE,
		/** Escort one of your ships. */
		ESCORT,
		/** Take (or keep) an objective. */
		CAPTURE,
		/** Go to a point (with its height) and hold there. */
		MOVE,
		/** Keep away from a ship or a point. */
		AVOID,
		/** Drop orders: fight freely. */
		CLEAR,
		/** Leave the battle. */
		RETREAT,
		/** Take command of that ship yourself: a shuttle carries you over (vanilla's transfer of command). */
		TRANSFER,
		/** Vanilla's full assault, on or off (the whole fleet). */
		FULL_ASSAULT,
		/** Vanilla's full retreat (the whole fleet; can't be undone). */
		FULL_RETREAT
	}

	/** One order from the player, for the host to carry out. */
	public static final class Command {
		public final Kind kind;
		/** Who it's for; empty = the fleet (vanilla picks). */
		public final List<Ship3D> ships = new ArrayList<>();
		public Ship3D ship;
		public Objective3D objective;
		/** MOVE / AVOID on a point: where, height included. */
		public final Vec3 point = new Vec3();

		Command(Kind kind) {
			this.kind = kind;
		}

		@Override
		public String toString() {
			String what = ship != null ? ship.name : objective != null ? objective.name
					: String.format("(%.0f, %.0f, %.0f)", point.x, point.y, point.z);
			return kind + " " + what + " for " + (ships.isEmpty() ? "the fleet" : ships.size() + " ship(s)");
		}
	}

	/** Where an order just went (a fading marker on the HUD). */
	public static final class Ping {
		public final Vec3 pos = new Vec3();
		public final Kind kind;
		public float age;

		Ping(Vec3 at, Kind kind) {
			pos.set(at);
			this.kind = kind;
		}
	}

	// ---- camera rig (ChaseCamera reads these while tactical) ----
	public final Vec3 focus = new Vec3();
	/** The focus follows this ship; null after panning. */
	public Ship3D follow;
	/** Heading of the view, radians round +Y (0 = looking along -Z). */
	public float yaw;
	/** Extra pitch from orbiting, radians (added to the tuning's tactical pitch). */
	public float pitchOffset;
	/** Altitude of the ground plane clicks land on (the grid): the focus's height. */
	public float planeY;

	// ---- selection and pointing ----
	public final List<Ship3D> selected = new ArrayList<>();
	/** Ship or objective under the cursor this frame (or null). */
	public Ship3D hover;
	public Objective3D hoverObjective;
	/** Box selection in screen pixels (origin bottom-left), while dragging. */
	public boolean boxing;
	public float boxX0, boxY0, boxX1, boxY1;

	/** Right button: nothing, pressed (deciding), dragging a height, ring menu open. */
	public enum Gesture { NONE, PRESSED, HEIGHT, RING }

	public Gesture gesture = Gesture.NONE;
	/** Right press: on the ground (plane point), on a ship, on an objective. */
	public final Vec3 ground = new Vec3();
	public boolean groundValid;
	public Ship3D pressShip;
	public Objective3D pressObjective;
	/** Height above the plane being dragged (HEIGHT). */
	public float lift;
	/** Screen position of the right press (ring centre). */
	public float pressX, pressY;
	/** Ring menu options and which one the mouse is over (-1 = none). */
	public final List<Kind> ring = new ArrayList<>();
	public int ringChoice = -1;

	public final List<Command> issued = new ArrayList<>();
	public final List<Ping> pings = new ArrayList<>();
	/** One-word status flag from the host (e.g. NO CP) and how long it stays up. */
	public String flag;
	public float flagTime;

	/** Fleet buttons: shown (a real battle), vanilla's state (host-set), the button under the mouse (-1 none). */
	public boolean fleetButtons, fullAssault, fullRetreat;
	public int buttonHover = -1;
	/** Full retreat was clicked once: a second click within this many seconds confirms it. */
	public float retreatConfirm;
	public static final int BUTTON_ASSAULT = 0, BUTTON_RETREAT = 1;
	public static final float BUTTON_W = 260f, BUTTON_H = 30f;

	/** Pixels the mouse may move and still count as a click; seconds to hold still for the ring. */
	static final float CLICK_PX = 6f, RING_HOLD = 0.35f, RING_PICK_PX = 22f, DOUBLE_CLICK = 0.35f;
	public static final float RING_RADIUS_PX = 70f;
	/** Height per wheel notch while the right button is down, as a share of the camera distance. */
	static final float WHEEL_LIFT = 0.06f;

	private boolean lmb, rmb, wasActive, lmbOnButton;
	private float lmbX, lmbY, rmbAt, lastClickTime = -10f, clock, wheelLift;
	private Ship3D lastClickShip;
	private float lastMouseX, lastMouseY;

	/** Entering the view: look at the given ship, from behind it. */
	public void enter(Ship3D s) {
		follow = s;
		if (s != null) {
			focus.set(s.pos);
			Vec3 f = s.rot.forward(new Vec3());
			if (f.x * f.x + f.z * f.z > 1e-4f) yaw = (float) Math.atan2(-f.x, -f.z);
		}
		pitchOffset = 0f;
		gesture = Gesture.NONE;
		boxing = false;
		lmb = rmb = lmbOnButton = false;
		retreatConfirm = 0f;
	}

	/** Screen rectangle {x0, y0, x1, y1} (origin bottom-left) of a fleet button, bottom right. */
	public static float[] buttonRect(int i, float w, float h) {
		float x1 = w - 24f, x0 = x1 - BUTTON_W, y0 = 28f + (1 - i) * (BUTTON_H + 16f);
		return new float[] {x0, y0, x1, y0 + BUTTON_H};
	}

	private int buttonAt(float x, float y, float w, float h) {
		if (!fleetButtons) return -1;
		for (int i = 0; i < 2; i++) {
			float[] r = buttonRect(i, w, h);
			if (x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]) return i;
		}
		return -1;
	}

	/**
	 * The wheel while the right button is down on empty space sets the point's height (true: used);
	 * otherwise it zooms.
	 */
	boolean wheel(float notches, ChaseCamera cam) {
		if (!rmb || !groundValid || gesture != Gesture.PRESSED && gesture != Gesture.HEIGHT) return false;
		float step = notches * WHEEL_LIFT * cam.tacticalDistance();
		wheelLift += step;
		lift = FlightModel.clamp(lift + step, -6000f, 6000f); // shows (and counts) at once, even if let go next frame
		gesture = Gesture.HEIGHT;
		return true;
	}

	/** Leaving: drop half-made gestures (the selection stays for next time). */
	public void leave() {
		gesture = Gesture.NONE;
		boxing = false;
		hover = null;
		hoverObjective = null;
	}

	/** Ground direction the view looks along, and its right. */
	public Vec3 heading(Vec3 out) {
		return out.set(-(float) Math.sin(yaw), 0f, -(float) Math.cos(yaw));
	}

	public Vec3 right(Vec3 out) {
		return out.set((float) Math.cos(yaw), 0f, -(float) Math.sin(yaw));
	}

	/** True for one of your own ships the player may order (not you, an ally's, a fighter or a station part). */
	public static boolean orderable(Sim sim, Ship3D s) {
		Ship3D p = sim.player;
		return s != null && p != null && s != p && s.warship() && !s.fixedPart() && !s.ally
				&& s.owner == p.owner && sim.ships.contains(s);
	}

	/**
	 * One frame while the view is up. Camera movement uses wall time (works while paused).
	 * @param shift Shift held (add to the selection)
	 */
	void update(Sim sim, PilotInput in, ChaseCamera cam, float realDt, boolean shift) {
		clock += realDt;
		if (!wasActive) {
			wasActive = true;
			lastMouseX = in.mouseX;
			lastMouseY = in.mouseY;
		}
		selected.removeIf(s -> !orderable(sim, s));
		if (follow != null && (follow.destroyed || !sim.ships.contains(follow))) follow = null;
		for (int i = pings.size() - 1; i >= 0; i--) {
			pings.get(i).age += realDt;
			if (pings.get(i).age > 1.5f) pings.remove(i);
		}
		if (flagTime > 0f) flagTime -= realDt;
		if (retreatConfirm > 0f) retreatConfirm -= realDt;
		buttonHover = buttonAt(in.mouseX, in.mouseY, in.screenW, in.screenH);

		moveCamera(sim, in, cam, realDt);
		pick(sim, in, cam);
		leftButton(sim, in, cam, shift);
		rightButton(sim, in, cam);
		lastMouseX = in.mouseX;
		lastMouseY = in.mouseY;
	}

	void deactivate() {
		wasActive = false;
		leave();
	}

	private void moveCamera(Sim sim, PilotInput in, ChaseCamera cam, float dt) {
		dt = Math.min(dt, 0.1f);
		float px = (in.strafeRight ? 1f : 0f) - (in.strafeLeft ? 1f : 0f);
		float pz = (in.throttleUp ? 1f : 0f) - (in.throttleDown ? 1f : 0f);
		if (px != 0f || pz != 0f) {
			follow = null;
			float speed = cam.tacticalDistance() * 0.9f; // a screen's depth in about a second, at any zoom
			Vec3 f = heading(new Vec3()), r = right(new Vec3());
			focus.addScaled(f, pz * speed * dt).addScaled(r, px * speed * dt);
		}
		float turn = (in.rollLeft ? 1f : 0f) - (in.rollRight ? 1f : 0f);
		yaw += turn * 1.6f * dt;
		if (in.freeLook) { // orbit by mouse
			yaw -= (in.mouseX - lastMouseX) / Math.max(1f, in.screenW) * 4f;
			pitchOffset = FlightModel.clamp(pitchOffset + (in.mouseY - lastMouseY) / Math.max(1f, in.screenH) * 2.5f, -0.6f, 0.55f);
		}
		if (follow != null) focus.set(follow.pos);
		if (sim.arenaHalfX > 0f) focus.x = FlightModel.clamp(focus.x, -sim.arenaHalfX * 1.2f, sim.arenaHalfX * 1.2f);
		if (sim.arenaHalfZ > 0f) focus.z = FlightModel.clamp(focus.z, -sim.arenaHalfZ * 1.2f, sim.arenaHalfZ * 1.2f);
		planeY = focus.y;
	}

	/** What's under the cursor: the nearest ship (any side) or objective on screen within its size. */
	private void pick(Sim sim, PilotInput in, ChaseCamera cam) {
		hover = null;
		hoverObjective = null;
		if (gesture == Gesture.HEIGHT || gesture == Gesture.RING || in.freeLook) return;
		float best = Float.MAX_VALUE;
		float[] sc = new float[3];
		for (Ship3D s : sim.ships) {
			if (!s.warship()) continue;
			if (!cam.toScreen(s.pos, in.screenW, in.screenH, sc)) continue;
			float r = Math.max(14f, cam.screenRadius(s, in.screenH) * 1.1f);
			float d = (float) Math.hypot(sc[0] - in.mouseX, sc[1] - in.mouseY);
			if (d <= r && d < best) {
				best = d;
				hover = s;
			}
		}
		if (hover != null) return;
		for (Objective3D o : sim.objectives) {
			if (!cam.toScreen(o.pos, in.screenW, in.screenH, sc)) continue;
			float d = (float) Math.hypot(sc[0] - in.mouseX, sc[1] - in.mouseY);
			if (d <= 18f && d < best) {
				best = d;
				hoverObjective = o;
			}
		}
	}

	private void leftButton(Sim sim, PilotInput in, ChaseCamera cam, boolean shift) {
		if (in.mouseLeft && !lmb) {
			lmb = true;
			lmbX = in.mouseX;
			lmbY = in.mouseY;
			lmbOnButton = false;
			if (gesture == Gesture.RING) { // the open ring takes the click: an option, or close
				if (ringChoice >= 0 && ringChoice < ring.size()) issueKind(sim, ring.get(ringChoice));
				gesture = Gesture.NONE;
				lmbOnButton = true; // swallow the rest of this click
				return;
			}
			int b = buttonAt(in.mouseX, in.mouseY, in.screenW, in.screenH);
			if (b >= 0) {
				pressButton(b);
				lmbOnButton = true;
				return;
			}
		}
		if (lmbOnButton) {
			if (!in.mouseLeft) lmb = lmbOnButton = false;
			return;
		}
		if (lmb && in.mouseLeft) {
			boxing = Math.hypot(in.mouseX - lmbX, in.mouseY - lmbY) > CLICK_PX;
			boxX0 = lmbX;
			boxY0 = lmbY;
			boxX1 = in.mouseX;
			boxY1 = in.mouseY;
			return;
		}
		if (!lmb) return;
		lmb = false; // released
		if (boxing) {
			boxing = false;
			if (!shift) selected.clear();
			float x0 = Math.min(boxX0, boxX1), x1 = Math.max(boxX0, boxX1), y0 = Math.min(boxY0, boxY1), y1 = Math.max(boxY0, boxY1);
			float[] sc = new float[3];
			for (Ship3D s : sim.ships) {
				if (!orderable(sim, s) || selected.contains(s)) continue;
				if (cam.toScreen(s.pos, in.screenW, in.screenH, sc) && sc[0] >= x0 && sc[0] <= x1 && sc[1] >= y0 && sc[1] <= y1) selected.add(s);
			}
			return;
		}
		Ship3D s = hover;
		boolean dbl = s != null && s == lastClickShip && clock - lastClickTime < DOUBLE_CLICK;
		lastClickShip = s;
		lastClickTime = clock;
		if (dbl) {
			follow = s; // double click: the view follows it
			return;
		}
		if (s != null && orderable(sim, s)) {
			if (shift) {
				if (!selected.remove(s)) selected.add(s);
			} else {
				selected.clear();
				selected.add(s);
			}
		} else if (s == null && hoverObjective == null && !shift) {
			selected.clear();
		}
	}

	/** A fleet button clicked: full assault toggles; full retreat needs a second click. */
	private void pressButton(int b) {
		if (b == BUTTON_ASSAULT) {
			issued.add(new Command(Kind.FULL_ASSAULT));
		} else if (!fullRetreat) {
			if (retreatConfirm > 0f) {
				retreatConfirm = 0f;
				issued.add(new Command(Kind.FULL_RETREAT));
			} else {
				retreatConfirm = 3f;
			}
		}
	}

	private void rightButton(Sim sim, PilotInput in, ChaseCamera cam) {
		if (gesture == Gesture.RING) { // open ring: follow the mouse; a right press closes it
			float dx = in.mouseX - pressX, dy = in.mouseY - pressY;
			ringChoice = -1;
			if (Math.hypot(dx, dy) > RING_PICK_PX && !ring.isEmpty()) {
				double a = Math.atan2(dy, dx); // option 0 straight up, then clockwise
				double fromTop = (Math.PI / 2 - a + 4 * Math.PI) % (2 * Math.PI);
				ringChoice = (int) Math.round(fromTop / (2 * Math.PI / ring.size())) % ring.size();
			}
			if (in.mouseRight && !rmb) {
				gesture = Gesture.NONE;
				rmb = true; // this press only closes the ring
				rmbAt = -1f;
			}
			return;
		}
		if (in.mouseRight && !rmb) { // press
			rmb = true;
			rmbAt = clock;
			pressX = in.mouseX;
			pressY = in.mouseY;
			pressShip = hover;
			pressObjective = pressShip == null ? hoverObjective : null;
			groundValid = pressShip == null && pressObjective == null && groundPoint(in, cam, ground);
			lift = wheelLift = 0f;
			ringChoice = -1;
			gesture = Gesture.PRESSED;
			return;
		}
		if (rmb && in.mouseRight) { // held
			if (rmbAt < 0f) return; // the press that closed a ring
			float dx = in.mouseX - pressX, dy = in.mouseY - pressY;
			if (gesture == Gesture.PRESSED) {
				// Up or down at any time while held sets the height (never the ring: it opens on release).
				if (groundValid && Math.abs(dy) > CLICK_PX && Math.abs(dy) > Math.abs(dx)) gesture = Gesture.HEIGHT;
				else if (Math.hypot(dx, dy) > CLICK_PX * 3f) gesture = Gesture.NONE; // a sideways drag: nothing
			}
			if (gesture == Gesture.HEIGHT) {
				float perPx = cam.worldPerPixel(ground, in.screenH);
				lift = FlightModel.clamp(dy * perPx + wheelLift, -6000f, 6000f);
			}
			return;
		}
		if (!rmb) return;
		rmb = false; // released
		if (rmbAt < 0f) return;
		Gesture g = gesture;
		gesture = Gesture.NONE;
		switch (g) {
			case PRESSED:
				if (clock - rmbAt >= RING_HOLD) openRing(sim); // held still: the ring stays open for a left click
				else issueDefault(sim);
				break;
			case HEIGHT: {
				Command c = command(Kind.MOVE);
				c.point.set(ground).add(new Vec3(0f, lift, 0f));
				issue(c, c.point);
				break;
			}
			default:
				break;
		}
	}

	/** Holding the right button still long enough that letting go will open the ring (HUD hint). */
	public float ringHoldFraction() {
		return gesture == Gesture.PRESSED && rmb && rmbAt >= 0f ? Math.min(1f, (clock - rmbAt) / RING_HOLD) : 0f;
	}

	private void openRing(Sim sim) {
		ring.clear();
		Ship3D p = sim.player;
		Ship3D t = pressShip;
		if (t != null && p != null && t.owner != p.owner) {
			ring.add(Kind.ENGAGE);
			ring.add(Kind.AVOID);
		} else if (t != null && p != null && !t.ally) { // one of yours (your own ship included)
			ring.add(Kind.ESCORT);
			if (t != p && orderable(sim, t)) ring.add(Kind.TRANSFER);
		} else if (pressObjective != null) {
			ring.add(Kind.CAPTURE);
			ring.add(Kind.AVOID);
		} else if (groundValid) {
			ring.add(Kind.MOVE);
			ring.add(Kind.AVOID);
		}
		if (!selected.isEmpty()) {
			ring.add(Kind.CLEAR);
			ring.add(Kind.RETREAT);
		}
		gesture = ring.isEmpty() ? Gesture.NONE : Gesture.RING;
	}

	/** Plain right click: the obvious order for what was under the cursor. */
	private void issueDefault(Sim sim) {
		Ship3D p = sim.player;
		if (pressShip != null && p != null) {
			if (pressShip.owner != p.owner) issueKind(sim, Kind.ENGAGE);
			else if (!selected.contains(pressShip) && !pressShip.ally) issueKind(sim, Kind.ESCORT); // yours, not one of those being ordered
		} else if (pressObjective != null) {
			issueKind(sim, Kind.CAPTURE);
		} else if (groundValid) {
			issueKind(sim, Kind.MOVE);
		}
	}

	private void issueKind(Sim sim, Kind kind) {
		if (kind == Kind.TRANSFER) { // handled here, not by the host: the shuttle is part of the 3D battle
			if (pressShip != null) {
				sim.startTransfer(pressShip);
				pings.add(new Ping(pressShip.pos, kind));
			}
			return;
		}
		Command c = command(kind);
		Vec3 at;
		if (pressShip != null) {
			c.ship = pressShip;
			at = pressShip.pos;
		} else if (pressObjective != null) {
			c.objective = pressObjective;
			at = pressObjective.pos;
		} else if (groundValid) {
			c.point.set(ground);
			at = ground;
		} else if (kind == Kind.CLEAR || kind == Kind.RETREAT) {
			at = sim.player != null ? sim.player.pos : focus;
		} else {
			return;
		}
		if (kind == Kind.ESCORT && c.ship == null) return;
		if ((kind == Kind.CLEAR || kind == Kind.RETREAT) && c.ships.isEmpty()) return;
		issue(c, at);
	}

	private Command command(Kind kind) {
		Command c = new Command(kind);
		c.ships.addAll(selected);
		return c;
	}

	private void issue(Command c, Vec3 at) {
		issued.add(c);
		pings.add(new Ping(at, c.kind));
	}

	/** Where the mouse ray meets the ground plane (false if it points above the horizon). */
	public boolean groundPoint(PilotInput in, ChaseCamera cam, Vec3 out) {
		float nx = in.mouseX / Math.max(1f, in.screenW) * 2f - 1f, ny = in.mouseY / Math.max(1f, in.screenH) * 2f - 1f;
		Vec3 d = cam.rayDir(nx, ny, new Vec3());
		if (d.y > -0.02f) return false;
		float t = (planeY - cam.pos.y) / d.y;
		if (t <= 0f || t > 200000f) return false;
		out.set(cam.pos).addScaled(d, t);
		return true;
	}

	/** Shows a one-word flag on the status line for a couple of seconds. */
	public void flag(String word) {
		flag = word;
		flagTime = 2.5f;
	}
}

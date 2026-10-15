package s3d.render;

/** GLSL 1.20 sources (the game runs a legacy OpenGL 2.1 context on macOS). */
final class Shaders {

	private Shaders() {}

	// ---- ship hull: relief mesh textured with the sprite, lit by the local star ----
	static final String SHIP_VS = """
			#version 120
			uniform mat4 u_mvp;
			uniform mat4 u_model;
			uniform vec2 u_snap; // retro wobble: snap grid in NDC steps per unit (0 = off)
			attribute vec3 a_pos;
			attribute vec3 a_nrm;
			attribute vec2 a_uv;
			attribute float a_side;
			varying vec3 v_nrm;
			varying vec2 v_uv;
			varying float v_side;
			varying vec3 v_wpos;
			varying vec3 v_tan;
			varying float v_ny;
			void main() {
				vec4 wp = u_model * vec4(a_pos, 1.0);
				v_ny = a_nrm.y;
				v_wpos = wp.xyz;
				mat3 m3 = mat3(u_model[0].xyz, u_model[1].xyz, u_model[2].xyz);
				v_nrm = m3 * a_nrm;
				v_tan = m3 * vec3(1.0, 0.0, 0.0);
				v_uv = a_uv;
				v_side = a_side;
				vec4 p = u_mvp * vec4(a_pos, 1.0);
				if (u_snap.x > 0.0 && p.w > 0.0) p.xy = floor(p.xy / p.w * u_snap + 0.5) / u_snap * p.w;
				gl_Position = p;
			}
			""";

	// ---- retro filter composite: low-res colour, nearest-sampled, quantised with a 4x4 ordered dither ----
	static final String RETRO_VS = """
			#version 120
			varying vec2 v_uv;
			void main() {
				v_uv = gl_MultiTexCoord0.xy;
				gl_Position = gl_Vertex;
			}
			""";

	/** u_useDepth 1: ship pass (transparent where no ship, writes the ships' depth); 0: whole scene. */
	static final String RETRO_FS = """
			#version 120
			uniform sampler2D u_color;
			uniform sampler2D u_depth;
			uniform vec2 u_size;
			uniform float u_levels;
			uniform float u_dither;
			uniform float u_useDepth;
			varying vec2 v_uv;
			float bayer2(vec2 p) {
				return mod(2.0 * p.x + 3.0 * p.y, 4.0);
			}
			void main() {
				vec2 px = min(floor(v_uv * u_size), u_size - 1.0);
				vec2 uv = (px + 0.5) / u_size;
				vec4 c = texture2D(u_color, uv);
				if (u_useDepth > 0.5) {
					if (c.a < 0.004) discard;
					gl_FragDepth = texture2D(u_depth, uv).r;
				} else {
					gl_FragDepth = gl_FragCoord.z;
				}
				if (u_levels > 0.0) {
					float b = (4.0 * bayer2(mod(px, 2.0)) + bayer2(mod(floor(px * 0.5), 2.0)) + 0.5) / 16.0;
					c.rgb = floor(c.rgb * u_levels + 0.5 + (b - 0.5) * u_dither) / u_levels;
				}
				gl_FragColor = c;
			}
			""";

	static final String SHIP_FS = """
			#version 120
			uniform sampler2D u_tex;
			uniform vec3 u_lightDir;
			uniform vec3 u_lightColor;
			uniform vec3 u_ambient;
			uniform vec3 u_fillDir;
			uniform vec3 u_fillColor;
			uniform vec3 u_camPos;
			uniform vec3 u_tint;
			uniform float u_hitFlash;
			uniform float u_dead;
			uniform sampler2D u_nrm;
			uniform float u_bump;
			uniform float u_crisp; // "crisp" relief profile: crease shading strength (0 = other profiles)
			varying vec3 v_nrm;
			varying vec2 v_uv;
			varying float v_side;
			varying vec3 v_wpos;
			varying vec3 v_tan;
			varying float v_ny;
			void main() {
				vec3 base = texture2D(u_tex, v_uv).rgb;
				float lum = dot(base, vec3(0.299, 0.587, 0.114));
				float bumpW = 1.0;
				if (v_side > 2.5) {
					// hand-made model: the same three looks, blended by which way the surface faces the ship
					float wallW = 1.0 - smoothstep(0.3, 0.7, abs(v_ny));
					float underW = 1.0 - smoothstep(-0.5, -0.1, v_ny);
					vec3 under = mix(mix(vec3(lum), base, 0.55) * 0.8, u_tint * 0.7, 0.3);
					base = mix(base, under, underW);
					base = mix(base, mix(base, u_tint, 0.4) * 0.8, wallW);
					bumpW = 1.0 - wallW;
				} else if (v_side > 1.5) {
					// edge wall: hull colour from the sprite's edge texels, slightly darkened
					// (crisp: the sprite's rim folded down over the wall, kept as it is)
					base = u_crisp > 0.0 ? base * 0.85 : mix(base, u_tint, 0.4) * 0.8;
				} else if (v_side > 0.5) {
					// underside: same panels, desaturated and darker, pulled toward the hull tint
					base = mix(vec3(lum), base, 0.55) * 0.8;
					base = mix(base, u_tint * 0.7, 0.3);
				}
				vec3 n = normalize(v_nrm);
				if (!gl_FrontFacing) n = -n;
				if ((v_side < 1.5 || v_side > 2.5) && u_bump > 0.0) {
					// Panel detail: tangent along the sprite's x axis, bitangent down the image (+z).
					vec3 T = normalize(v_tan - n * dot(v_tan, n));
					vec3 B = normalize(cross(T, n));
					vec2 g = texture2D(u_nrm, v_uv).rg * 2.0 - 1.0;
					n = normalize(n + (g.x * T + g.y * B) * u_bump * bumpW);
				}
				vec3 V = normalize(u_camPos - v_wpos);
				float ndl = max(dot(n, u_lightDir), 0.0);
				vec3 H = normalize(u_lightDir + V);
				float spec = pow(max(dot(n, H), 0.0), 32.0) * (0.12 + 0.4 * lum);
				float rim = pow(1.0 - max(dot(n, V), 0.0), 3.0) * 0.12;
				vec3 amb = u_ambient * (0.75 + 0.25 * n.y);
				float fill = max(dot(n, u_fillDir), 0.0);
				float edge = 0.0;
				if (u_crisp > 0.0 && v_side < 2.5) {
					// matte plating (the sprite already has its light painted in) + baked crease shading
					edge = clamp((fract(v_side) - 0.25) / 0.2, -1.0, 1.0) * u_crisp;
					spec = pow(max(dot(n, H), 0.0), 24.0) * (0.04 + 0.14 * lum);
					rim *= 0.5;
				}
				vec3 col = base * (amb + u_lightColor * ndl + u_fillColor * fill) + u_lightColor * spec + (u_ambient + u_fillColor) * rim;
				if (edge < 0.0) col *= 1.0 + 0.45 * edge;
				else col += (base * 0.6 + vec3(0.05)) * edge * (0.35 + 0.65 * ndl);
				col = mix(col, col * vec3(0.22, 0.2, 0.2), u_dead);
				col += vec3(1.0, 0.55, 0.25) * u_hitFlash;
				gl_FragColor = vec4(col, 1.0);
			}
			""";

	// ---- generic effects / HUD, fed by immediate-mode vertices ----
	static final String FX_VS = """
			#version 120
			uniform mat4 u_vp;
			varying vec4 v_col;
			varying vec4 v_col2;
			varying vec2 v_uv;
			void main() {
				v_col = gl_Color;
				v_col2 = gl_SecondaryColor;
				v_uv = gl_MultiTexCoord0.xy;
				gl_Position = u_vp * gl_Vertex;
			}
			""";

	/** Modes: 0 vertex colour, 1 engine flame, 2 radial glow, 3 text, 4 texture copy, 5 projectile streak, 6 beam (core = secondary colour), 8 shockwave ring, 9 soft puff (real alpha), 10 tinted sprite (real alpha). */
	static final String FX_FS = """
			#version 120
			uniform int u_mode;
			uniform sampler2D u_tex;
			varying vec4 v_col;
			varying vec4 v_col2;
			varying vec2 v_uv;
			void main() {
				if (u_mode == 6) {
					float along = clamp(v_uv.x, 0.0, 1.0);
					float across = abs(v_uv.y * 2.0 - 1.0);
					float fringe = pow(max(1.0 - across, 0.0), 1.3);
					float core = pow(max(1.0 - across * 2.5, 0.0), 2.0);
					float tip = 1.0 - smoothstep(0.92, 1.0, along);
					vec3 c = (v_col.rgb * fringe + v_col2.rgb * core * 1.6) * tip;
					gl_FragColor = vec4(c * v_col.a, 1.0);
				} else if (u_mode == 5) {
					float along = clamp(v_uv.x, 0.0, 1.0);
					float across = abs(v_uv.y * 2.0 - 1.0);
					float fringe = pow(max(1.0 - across, 0.0), 1.5);
					float core = pow(max(1.0 - across * 2.2, 0.0), 2.0);
					float tail = pow(1.0 - along, 1.2);
					vec3 c = (v_col.rgb * fringe + v_col2.rgb * core * 1.4) * tail;
					gl_FragColor = vec4(c * v_col.a, 1.0);
				} else if (u_mode == 10) {
					// A sprite (sheet cell) tinted by the vertex colour, with its own alpha: swarm pieces, hit glows.
					vec4 t = texture2D(u_tex, v_uv);
					gl_FragColor = vec4(t.rgb * v_col.rgb, t.a * v_col.a);
				} else if (u_mode == 9) {
					// Soft puff with real alpha (blended normally): the shroud's dark murk.
					vec2 d = v_uv * 2.0 - 1.0;
					float r2 = dot(d, d);
					float a = exp(-r2 * 3.0) * (1.0 - smoothstep(0.75, 1.0, r2));
					gl_FragColor = vec4(v_col.rgb, a * v_col.a);
				} else if (u_mode == 8) {
					vec2 d = v_uv * 2.0 - 1.0;
					float r = length(d);
					float a = exp(-pow((r - 0.86) / 0.05, 2.0)) + 0.3 * exp(-pow((r - 0.78) / 0.16, 2.0));
					a *= 1.0 - smoothstep(0.96, 1.0, r);
					gl_FragColor = vec4(v_col.rgb * a * v_col.a, 1.0);
				} else if (u_mode == 0) {
					gl_FragColor = v_col;
				} else if (u_mode == 1) {
					float along = clamp(v_uv.x, 0.0, 1.0);
					float across = abs(v_uv.y * 2.0 - 1.0);
					float body = pow(1.0 - along, 1.4) * pow(max(1.0 - across, 0.0), 1.6);
					float core = pow(1.0 - along, 5.0) * pow(max(1.0 - across * 1.8, 0.0), 2.5);
					vec3 c = v_col.rgb * body + vec3(1.0, 0.97, 0.92) * core;
					gl_FragColor = vec4(c * v_col.a, 1.0);
				} else if (u_mode == 2) {
					vec2 d = v_uv * 2.0 - 1.0;
					float r2 = dot(d, d);
					float a = exp(-r2 * 5.0) * (1.0 - smoothstep(0.8, 1.0, r2));
					gl_FragColor = vec4(v_col.rgb * a * v_col.a, 1.0);
				} else if (u_mode == 3) {
					gl_FragColor = vec4(v_col.rgb, v_col.a * texture2D(u_tex, v_uv).a);
				} else {
					gl_FragColor = vec4(texture2D(u_tex, v_uv).rgb, 1.0);
				}
			}
			""";

	// ---- shields: additive sphere cap, brighter at grazing angles, the rim and fresh hits ----
	static final String SHIELD_VS = """
			#version 120
			uniform mat4 u_vp;
			varying vec3 v_pos;
			varying vec3 v_nrm;
			varying float v_edge;
			void main() {
				v_pos = gl_Vertex.xyz;
				v_nrm = gl_Normal;
				v_edge = gl_MultiTexCoord0.x;
				gl_Position = u_vp * gl_Vertex;
			}
			""";

	static final String SHIELD_FS = """
			#version 120
			uniform vec3 u_cam;
			uniform vec3 u_inner;
			uniform vec3 u_ring;
			uniform float u_alpha;
			uniform float u_bright;
			uniform vec4 u_hit0;
			uniform vec4 u_hit1;
			uniform vec4 u_hit2;
			uniform vec4 u_hit3;
			varying vec3 v_pos;
			varying vec3 v_nrm;
			varying float v_edge;
			float hit(vec4 h, vec3 n) {
				return h.w * smoothstep(0.86, 1.0, dot(n, h.xyz));
			}
			void main() {
				vec3 n = normalize(v_nrm);
				vec3 v = normalize(u_cam - v_pos);
				float fres = pow(1.0 - abs(dot(n, v)), 2.2);
				float rim = smoothstep(0.86, 1.0, v_edge);
				float glow = hit(u_hit0, n) + hit(u_hit1, n) + hit(u_hit2, n) + hit(u_hit3, n);
				vec3 c = u_inner * (0.015 + 0.2 * fres) + u_ring * (rim * 0.4 + glow * 0.5);
				gl_FragColor = vec4(c * u_alpha * u_bright, 1.0);
			}
			""";

	// ---- target hologram: the target's own mesh, additive cyan, armor damage by direction ----
	static final String HOLO_VS = """
			#version 120
			uniform mat4 u_mvp;
			uniform mat4 u_model;
			attribute vec3 a_pos;
			attribute vec3 a_nrm;
			varying vec3 v_local;
			varying vec3 v_lnrm;
			varying vec3 v_nrm;
			void main() {
				v_local = a_pos;
				v_lnrm = a_nrm;
				v_nrm = mat3(u_model[0].xyz, u_model[1].xyz, u_model[2].xyz) * a_nrm;
				gl_Position = u_mvp * vec4(a_pos, 1.0);
			}
			""";

	/** Armor: 12 directions (0 = nose) x top / side / underside, as ArmorShell stores it. */
	static final String HOLO_FS = """
			#version 120
			uniform float u_armor[36];
			uniform float u_time;
			uniform float u_hull;
			uniform vec3 u_col;
			varying vec3 v_local;
			varying vec3 v_lnrm;
			varying vec3 v_nrm;
			void main() {
				vec3 n = normalize(v_nrm);
				float fres = pow(1.0 - abs(n.z), 1.5);
				float az = atan(v_local.x, -v_local.z);
				int a = int(mod(floor((az + 3.14159265) / 6.2831853 * 12.0), 12.0));
				int e = v_lnrm.y > 0.5 ? 0 : (v_lnrm.y < -0.5 ? 2 : 1);
				float frac = u_armor[e * 12 + a];
				vec3 dmg = mix(vec3(1.0, 0.12, 0.08), vec3(1.0, 0.7, 0.25), frac);
				vec3 base = mix(dmg, u_col, smoothstep(0.9, 0.99, frac));
				float scan = 0.65 + 0.35 * sin(v_local.z * 0.8 - u_time * 5.0);
				vec3 c = base * (0.1 + 0.9 * fres) * scan * (0.45 + 0.55 * u_hull);
				gl_FragColor = vec4(c, 1.0);
			}
			""";

	// ---- aura / jitter: a ship's own mesh, additive. Glow mode: pushed out a little along its normals,
	// bright at the silhouette (phase highlight, vent glow). Textured mode: the hull itself tinted, as
	// vanilla's jitter copies and weapon glow are tinted copies of the sprite. ----
	static final String AURA_VS = """
			#version 120
			uniform mat4 u_mvp;
			uniform mat4 u_model;
			uniform float u_inflate;
			attribute vec3 a_pos;
			attribute vec3 a_nrm;
			attribute vec2 a_uv;
			varying vec3 v_world;
			varying vec3 v_nrm;
			varying vec3 v_local;
			varying vec2 v_uv;
			void main() {
				vec3 p = a_pos + a_nrm * u_inflate;
				v_local = a_pos;
				v_uv = a_uv;
				v_nrm = mat3(u_model[0].xyz, u_model[1].xyz, u_model[2].xyz) * a_nrm;
				v_world = (u_model * vec4(p, 1.0)).xyz;
				gl_Position = u_mvp * vec4(p, 1.0);
			}
			""";

	/** u_bands: 0 = even glow, 1 = bands sweeping along the hull. u_textured: 0 = silhouette glow, 1 = tinted hull. */
	static final String AURA_FS = """
			#version 120
			uniform vec3 u_cam;
			uniform vec3 u_col;
			uniform float u_alpha;
			uniform float u_time;
			uniform float u_bands;
			uniform float u_textured;
			uniform sampler2D u_tex;
			varying vec3 v_world;
			varying vec3 v_nrm;
			varying vec3 v_local;
			varying vec2 v_uv;
			void main() {
				vec3 n = normalize(v_nrm);
				vec3 v = normalize(u_cam - v_world);
				float fres = pow(1.0 - abs(dot(n, v)), 2.0);
				float band = 1.0 - u_bands + u_bands * (0.5 + 0.5 * sin(v_local.z * 0.3 - u_time * 6.0));
				vec3 glow = u_col * (0.12 + 0.88 * fres) * band;
				vec3 tint = u_col * (0.2 + 1.6 * texture2D(u_tex, v_uv).rgb);
				gl_FragColor = vec4(mix(glow, tint, u_textured) * u_alpha, 1.0);
			}
			""";

	// ---- background: procedural nebula + local star, one full-screen pass ----
	static final String SKY_VS = """
			#version 120
			uniform vec3 u_right;
			uniform vec3 u_up;
			uniform vec3 u_fwd;
			uniform float u_tanX;
			uniform float u_tanY;
			varying vec3 v_ray;
			void main() {
				vec2 ndc = gl_Vertex.xy;
				v_ray = u_fwd + ndc.x * u_tanX * u_right + ndc.y * u_tanY * u_up;
				gl_Position = vec4(ndc, 0.0, 1.0);
			}
			""";

	static final String SKY_FS = """
			#version 120
			uniform vec3 u_sunDir;
			uniform vec3 u_sunColor;
			uniform vec3 u_nebA;
			uniform vec3 u_nebB;
			uniform float u_nebStrength;
			varying vec3 v_ray;
			float hash(vec3 p) {
				p = fract(p * 0.3183099 + vec3(0.11, 0.17, 0.13));
				p *= 17.0;
				return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
			}
			float noise(vec3 x) {
				vec3 i = floor(x);
				vec3 f = fract(x);
				f = f * f * (3.0 - 2.0 * f);
				return mix(mix(mix(hash(i), hash(i + vec3(1.0, 0.0, 0.0)), f.x),
				               mix(hash(i + vec3(0.0, 1.0, 0.0)), hash(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
				           mix(mix(hash(i + vec3(0.0, 0.0, 1.0)), hash(i + vec3(1.0, 0.0, 1.0)), f.x),
				               mix(hash(i + vec3(0.0, 1.0, 1.0)), hash(i + vec3(1.0, 1.0, 1.0)), f.x), f.y), f.z);
			}
			float fbm(vec3 p) {
				float a = 0.5;
				float s = 0.0;
				for (int i = 0; i < 5; i++) {
					s += a * noise(p);
					p = p * 2.03 + vec3(1.7, 9.2, 3.1);
					a *= 0.5;
				}
				return s;
			}
			void main() {
				vec3 d = normalize(v_ray);
				float n = fbm(d * 2.3 + vec3(3.0, 1.0, 7.0));
				float m = fbm(d * 4.1 + vec3(-2.0, 5.0, 1.0));
				float band = exp(-pow(d.y * 2.2 - 0.3 * n, 2.0));
				float dens = smoothstep(0.42, 0.85, n) * (0.55 + 0.45 * m);
				vec3 col = mix(u_nebA, u_nebB, smoothstep(0.3, 0.7, m)) * dens * 0.55 * u_nebStrength;
				col += vec3(0.05, 0.05, 0.08) * band * (0.5 + n) * u_nebStrength;
				col += vec3(0.006, 0.007, 0.012);
				float s = max(dot(d, u_sunDir), 0.0);
				float disc = smoothstep(0.99955, 0.99972, s);
				col += u_sunColor * (disc * 5.0 + pow(s, 140.0) * 0.45 + pow(s, 12.0) * 0.06);
				gl_FragColor = vec4(col, 1.0);
			}
			""";

	// ---- stars: point sprites at infinity ----
	static final String STAR_VS = """
			#version 120
			uniform mat4 u_vpRot;
			uniform float u_pointScale; // < 1 when the scene renders at low resolution (retro filter)
			attribute vec3 a_dir;
			attribute vec4 a_col;
			varying vec3 v_col;
			void main() {
				float size = a_col.a * u_pointScale;
				v_col = a_col.rgb * min(1.0, size * size); // sub-pixel stars dim instead of growing
				vec4 p = u_vpRot * vec4(a_dir, 1.0);
				gl_Position = p.xyww;
				gl_PointSize = max(size, 1.0);
			}
			""";

	static final String STAR_FS = """
			#version 120
			varying vec3 v_col;
			void main() {
				vec2 d = gl_PointCoord * 2.0 - 1.0;
				float a = clamp(1.0 - dot(d, d), 0.0, 1.0);
				gl_FragColor = vec4(v_col * a, 1.0);
			}
			""";
}

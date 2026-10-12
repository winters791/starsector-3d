package s3d.render;

/** GLSL 1.20 sources (the game runs a legacy OpenGL 2.1 context on macOS). */
final class Shaders {

	private Shaders() {}

	// ---- ship hull: relief mesh textured with the sprite, lit by the local star ----
	static final String SHIP_VS = """
			#version 120
			uniform mat4 u_mvp;
			uniform mat4 u_model;
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
				gl_Position = u_mvp * vec4(a_pos, 1.0);
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
					base = mix(base, u_tint, 0.4) * 0.8;
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
				vec3 col = base * (amb + u_lightColor * ndl + u_fillColor * fill) + u_lightColor * spec + (u_ambient + u_fillColor) * rim;
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

	/** Modes: 0 vertex colour, 1 engine flame, 2 radial glow, 3 text, 4 texture copy, 5 projectile streak, 6 beam (core = secondary colour). */
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
			attribute vec3 a_dir;
			attribute vec4 a_col;
			varying vec3 v_col;
			void main() {
				v_col = a_col.rgb;
				vec4 p = u_vpRot * vec4(a_dir, 1.0);
				gl_Position = p.xyww;
				gl_PointSize = a_col.a;
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

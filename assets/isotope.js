/*
 * Interactive illustrations for the Isotope site. The hero is an atom; the
 * demos show the rewrite, the trip between versions and the pre-flight check.
 * Rules and numbers in the demos are real (from Isotope's tables and the
 * 26.3 server test), but the animations are illustrations, not the mod.
 * Any element with data-demo="<name>" becomes a demo; the hero canvas uses
 * data-hero. Demos only animate while on screen.
 *
 * The canvas plumbing is shared with the Chlorine site. Wrapped in a function
 * so its names don't collide with site.js.
 */
(() => {

const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
let palette = {};

function readPalette() {
  const style = getComputedStyle(document.documentElement);
  const get = (name) => style.getPropertyValue(name).trim();
  palette = {
    bg: get("--bg-2"), surface: get("--surface"), ink: get("--ink"), muted: get("--muted"), faint: get("--faint"),
    line: get("--line"), lineStrong: get("--line-strong"), accent: get("--accent"), accent2: get("--accent-2"),
    warn: get("--warn"), bad: get("--bad"), good: get("--good"),
    light: document.documentElement.dataset.theme === "light",
  };
}
readPalette();
window.addEventListener("isotope:theme", readPalette);

function alpha(color, a) {
  if (color.startsWith("#")) {
    const n = parseInt(color.slice(1), 16);
    return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${a})`;
  }
  return color;
}

const rand = (min, max) => min + Math.random() * (max - min);
const clamp = (v, min, max) => Math.max(min, Math.min(max, v));
const lerp = (a, b, t) => a + (b - a) * t;

/* ---------- Shared canvas + loop plumbing ---------- */
function attachCanvas(canvas) {
  const ctx = canvas.getContext("2d");
  const view = { ctx, w: 0, h: 0, dpr: 1 };
  const resize = () => {
    const rect = canvas.getBoundingClientRect();
    view.dpr = Math.min(window.devicePixelRatio || 1, 2);
    view.w = rect.width;
    view.h = rect.height;
    canvas.width = Math.max(1, Math.round(rect.width * view.dpr));
    canvas.height = Math.max(1, Math.round(rect.height * view.dpr));
    ctx.setTransform(view.dpr, 0, 0, view.dpr, 0, 0);
  };
  resize();
  if ("ResizeObserver" in window) new ResizeObserver(resize).observe(canvas);
  else window.addEventListener("resize", resize);
  return view;
}

function runWhileVisible(element, frame) {
  let visible = false;
  let last = 0;
  let raf = 0;
  const loop = (now) => {
    const dt = Math.min(0.05, (now - last) / 1000 || 0.016);
    last = now;
    frame(dt, now / 1000);
    raf = requestAnimationFrame(loop);
  };
  const update = () => {
    const shouldRun = visible && !document.hidden && element.offsetParent !== null;
    if (shouldRun && !raf) { last = performance.now(); raf = requestAnimationFrame(loop); }
    if (!shouldRun && raf) { cancelAnimationFrame(raf); raf = 0; }
  };
  new IntersectionObserver(([entry]) => { visible = entry.isIntersecting; update(); }).observe(element);
  document.addEventListener("visibilitychange", update);
  window.addEventListener("resize", update);
  // Tab panels toggle `hidden`, which IntersectionObserver reports, but poll cheaply as a fallback.
  setInterval(update, 500);
}

function pointerOn(canvas, view) {
  const pointer = { x: 0, y: 0, active: false, down: false };
  const set = (event) => {
    const rect = canvas.getBoundingClientRect();
    pointer.x = event.clientX - rect.left;
    pointer.y = event.clientY - rect.top;
  };
  canvas.addEventListener("pointermove", (event) => { set(event); pointer.active = true; });
  canvas.addEventListener("pointerdown", (event) => { set(event); pointer.active = true; pointer.down = true; });
  canvas.addEventListener("pointerup", () => { pointer.down = false; });
  canvas.addEventListener("pointerleave", () => { pointer.active = false; pointer.down = false; });
  return pointer;
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.roundRect ? ctx.roundRect(x, y, w, h, r) : ctx.rect(x, y, w, h);
}

/* Builds the demo chrome (header, canvas, controls, stats) inside `host`. */
function buildDemo(host, options) {
  host.classList.add("demo");
  host.innerHTML = `
    <div class="demo-head">
      <div><div class="demo-title">${options.title}</div><p class="demo-sub">${options.sub}</p></div>
    </div>
    <div class="demo-canvas${options.tall ? " tall" : ""}">
      <canvas aria-hidden="true"></canvas>
      ${options.label ? `<span class="overlay-label">${options.label}</span>` : ""}
    </div>
    <div class="demo-foot">
      ${options.controls || ""}
      <div class="stats" aria-live="polite">
        ${(options.stats || []).map(([key, label]) => `<div class="stat">${label}<b data-stat="${key}">–</b></div>`).join("")}
      </div>
    </div>`;
  const canvas = host.querySelector("canvas");
  const view = attachCanvas(canvas);
  const stats = {};
  host.querySelectorAll("[data-stat]").forEach((element) => { stats[element.dataset.stat] = element; });
  host.querySelectorAll('input[type="range"]').forEach((input) => {
    const out = host.querySelector(`output[for="${input.id}"]`);
    const sync = () => {
      input.style.setProperty("--fill", `${((input.value - input.min) / (input.max - input.min)) * 100}%`);
      if (out) out.textContent = (input.dataset.format || "{}").replace("{}", input.value);
    };
    input.addEventListener("input", sync);
    sync();
  });
  const lastText = {};
  return {
    canvas, view,
    q: (selector) => host.querySelector(selector),
    stat(key, text, tone = "") {
      const element = stats[key];
      if (!element || lastText[key] === text + tone) return;
      lastText[key] = text + tone;
      element.textContent = text;
      element.className = tone;
    },
  };
}

let uid = 0;
const switchHtml = (label, checked = true) => {
  const id = `sw${++uid}`;
  return `<label class="switch" for="${id}"><input type="checkbox" id="${id}" data-role="toggle" ${checked ? "checked" : ""}><span class="track"></span>${label}</label>`;
};
const rangeHtml = (label, min, max, value, format = "{}", role = "range") => {
  const id = `rg${++uid}`;
  return `<label class="range" for="${id}">${label}<input type="range" id="${id}" data-role="${role}" min="${min}" max="${max}" value="${value}" data-format="${format}"><output for="${id}"></output></label>`;
};
const buttonHtml = (label, role = "action") => `<button class="btn btn-ghost btn-sm" type="button" data-role="${role}">${label}</button>`;


/* ---------- Hero: an atom whose two nucleus particles are the two versions ---------- */
const PAIRS = [["26.1.2", "26.3"], ["26.3", "26.2"], ["26.2", "26.3"], ["26.1.2", "26.2"]];

function heroScene(canvas) {
  const view = attachCanvas(canvas);
  const pointer = pointerOn(canvas, view);
  const readFrom = document.querySelector("[data-hero-from]");
  const readTo = document.querySelector("[data-hero-to]");
  let tiltX = 0.35, tiltY = 0;
  let pairIndex = 0;
  let pulse = 0;
  let nextSwap = 4;
  const orbits = [0, Math.PI / 3, (2 * Math.PI) / 3].map((spin, i) => ({ spin, speed: 0.55 + i * 0.18, phase: rand(0, Math.PI * 2) }));
  const trail = orbits.map(() => []);

  const project = (x, y, z, cx, cy) => {
    // Rotate around X (tilt) then Y (turn), then a gentle perspective.
    const cosX = Math.cos(tiltX), sinX = Math.sin(tiltX);
    const y1 = y * cosX - z * sinX, z1 = y * sinX + z * cosX;
    const cosY = Math.cos(tiltY), sinY = Math.sin(tiltY);
    const x2 = x * cosY + z1 * sinY, z2 = -x * sinY + z1 * cosY;
    const p = 1 / (1 + z2 / 900);
    return { x: cx + x2 * p, y: cy + y1 * p, z: z2, p };
  };

  runWhileVisible(canvas, (dt, t) => {
    const { ctx, w, h } = view;
    const cx = w / 2, cy = h / 2;
    const r = Math.min(w, h) * 0.38;
    const targetX = pointer.active ? 0.2 + ((pointer.y / h) - 0.5) * 1.1 : 0.35 + Math.sin(t * 0.3) * 0.12;
    const targetY = pointer.active ? ((pointer.x / w) - 0.5) * 1.3 : Math.sin(t * 0.21) * 0.25;
    tiltX = lerp(tiltX, targetX, 0.05);
    tiltY = lerp(tiltY, targetY, 0.05);

    nextSwap -= dt;
    if (nextSwap <= 0 && !reduceMotion) {
      nextSwap = 4;
      pairIndex = (pairIndex + 1) % PAIRS.length;
      pulse = 1;
      if (readFrom) readFrom.textContent = PAIRS[pairIndex][0];
      if (readTo) readTo.textContent = PAIRS[pairIndex][1];
    }
    pulse = Math.max(0, pulse - dt * 1.4);

    ctx.clearRect(0, 0, w, h);
    const glow = ctx.createRadialGradient(cx, cy, 0, cx, cy, r * 1.3);
    glow.addColorStop(0, alpha(palette.accent, palette.light ? 0.14 : 0.22));
    glow.addColorStop(1, alpha(palette.accent, 0));
    ctx.fillStyle = glow;
    ctx.fillRect(0, 0, w, h);

    const electrons = [];
    orbits.forEach((orbit, i) => {
      // Each orbit is an ellipse in its own plane, rotated by `spin` around the view axis.
      ctx.beginPath();
      for (let k = 0; k <= 96; k++) {
        const a = (k / 96) * Math.PI * 2;
        const ex = Math.cos(a) * r, ey = Math.sin(a) * r * 0.36;
        const x = ex * Math.cos(orbit.spin) - ey * Math.sin(orbit.spin);
        const y = ex * Math.sin(orbit.spin) + ey * Math.cos(orbit.spin);
        const pt = project(x, y, ey * 0.9, cx, cy);
        k ? ctx.lineTo(pt.x, pt.y) : ctx.moveTo(pt.x, pt.y);
      }
      ctx.strokeStyle = alpha(palette.accent, palette.light ? 0.42 : 0.5);
      ctx.lineWidth = 1.4;
      ctx.stroke();

      const a = orbit.phase + t * orbit.speed * (reduceMotion ? 0 : 1);
      const ex = Math.cos(a) * r, ey = Math.sin(a) * r * 0.36;
      const x = ex * Math.cos(orbit.spin) - ey * Math.sin(orbit.spin);
      const y = ex * Math.sin(orbit.spin) + ey * Math.cos(orbit.spin);
      const pt = project(x, y, ey * 0.9, cx, cy);
      trail[i].push(pt);
      if (trail[i].length > 18) trail[i].shift();
      electrons.push(pt);
    });

    trail.forEach((points) => points.forEach((pt, k) => {
      ctx.fillStyle = alpha(palette.accent2, (k / points.length) * 0.35);
      ctx.beginPath();
      ctx.arc(pt.x, pt.y, 2 + (k / points.length) * 2, 0, Math.PI * 2);
      ctx.fill();
    }));

    // Nucleus: a solid particle (the mod) and a hollow one (the new version number).
    const s = Math.min(w, h) * 0.055 * (1 + pulse * 0.25);
    const solid = palette.light ? palette.accent : "#f4f0ff";
    ctx.fillStyle = solid;
    ctx.beginPath();
    ctx.arc(cx - s * 0.75, cy, s, 0, Math.PI * 2);
    ctx.fill();
    ctx.lineWidth = s * 0.42;
    ctx.strokeStyle = solid;
    ctx.beginPath();
    ctx.arc(cx + s * 0.95, cy, s * 0.72, 0, Math.PI * 2);
    ctx.stroke();
    if (pulse > 0) {
      ctx.strokeStyle = alpha(palette.accent2, pulse * 0.6);
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(cx, cy, s * 2.2 + (1 - pulse) * r * 0.8, 0, Math.PI * 2);
      ctx.stroke();
    }

    electrons.forEach((pt) => {
      ctx.fillStyle = solid;
      ctx.beginPath();
      ctx.arc(pt.x, pt.y, 5.5 * pt.p, 0, Math.PI * 2);
      ctx.fill();
    });
  });
}

/* ---------- Demo 1: the rewrite, line by line (real rules from the 26.2 -> 26.3 tables) ---------- */
const REWRITES = [
  { before: "poseStack.mulPose(spin);", after: "poseStack.rotate(spin);", kind: "renamed", tone: "good" },
  { before: "import com.mojang.blaze3d.systems.CommandEncoder;", after: "import com.mojang.renderpearl.api.commands.CommandEncoder;", kind: "moved", tone: "good" },
  { before: "if (reaction == PushReaction.DESTROY)", after: "if (reaction == PushReaction.POPPED)", kind: "renamed", tone: "good" },
  { before: "server.getStructureManager()", after: "server.getStructureTemplateManager()", kind: "renamed", tone: "good" },
  { before: "CODEC = simpleCodec(MyBlock::new);", after: "CODEC = null; // codecs were removed", kind: "stub", tone: "warn" },
  { before: "extends ConfiguredFeature<...>", after: "extends ConfiguredFeature<...>", kind: "reported", tone: "bad" },
];

function demoRewrite(host) {
  const demo = buildDemo(host, {
    title: "The rewrite",
    sub: "A few lines from a 26.2 mod, run through the 26.2 → 26.3 rules. Every rule shown is a real one from Isotope's tables.",
    controls: buttonHtml("Rewrite for 26.3", "go") + buttonHtml("Reset", "reset"),
    stats: [["fixed", "Redirected"], ["stub", "Stubbed"], ["left", "Reported"]],
  });
  let progress = -1;
  let running = false;
  let mono = "13px DM Mono, monospace";
  demo.q('[data-role="go"]').addEventListener("click", () => { running = true; if (progress >= REWRITES.length) progress = -1; });
  demo.q('[data-role="reset"]').addEventListener("click", () => { running = false; progress = -1; });

  runWhileVisible(host, (dt) => {
    const { ctx, w, h } = demo.view;
    if (running) progress = Math.min(REWRITES.length + 0.001, progress + dt * (reduceMotion ? 10 : 1.1));
    if (progress >= REWRITES.length) running = false;
    ctx.clearRect(0, 0, w, h);
    const size = w < 520 ? 10.5 : 13;
    mono = `${size}px "DM Mono", ui-monospace, monospace`;
    ctx.font = mono;
    const rowH = Math.min(46, (h - 24) / REWRITES.length);
    let fixed = 0, stub = 0, left = 0;

    REWRITES.forEach((line, i) => {
      const y = 18 + i * rowH;
      const t = clamp(progress - i, 0, 1);
      if (t >= 1) { if (line.tone === "good") fixed++; else if (line.tone === "warn") stub++; else left++; }
      roundRect(ctx, 10, y, w - 20, rowH - 8, 10);
      ctx.fillStyle = t > 0 ? alpha(palette[line.tone], 0.08 * t) : alpha(palette.ink, palette.light ? 0.03 : 0.04);
      ctx.fill();
      const textY = y + (rowH - 8) / 2 + size * 0.35;
      // Old text slides up and fades; new text rises in.
      ctx.save();
      ctx.beginPath();
      ctx.rect(10, y, w - 20, rowH - 8);
      ctx.clip();
      ctx.globalAlpha = 1 - t;
      ctx.fillStyle = palette.muted;
      ctx.fillText(line.before, 24, textY - t * 14);
      ctx.globalAlpha = t;
      ctx.fillStyle = line.tone === "bad" ? palette.bad : palette.ink;
      ctx.fillText(line.after, 24, textY + (1 - t) * 14);
      ctx.restore();
      if (t > 0) {
        const tag = line.kind.toUpperCase();
        ctx.font = `600 ${size - 2}px "DM Mono", monospace`;
        const tw = ctx.measureText(tag).width + 16;
        ctx.globalAlpha = t;
        roundRect(ctx, w - 20 - tw, y + (rowH - 8) / 2 - 10, tw, 20, 10);
        ctx.fillStyle = alpha(palette[line.tone], 0.16);
        ctx.fill();
        ctx.fillStyle = palette[line.tone];
        ctx.fillText(tag, w - 12 - tw, y + (rowH - 8) / 2 + 4);
        ctx.globalAlpha = 1;
        ctx.font = mono;
      }
    });

    demo.stat("fixed", String(fixed), fixed ? "good" : "");
    demo.stat("stub", String(stub), stub ? "warn" : "");
    demo.stat("left", String(left), left ? "bad" : "");
  });
}

/* ---------- Demo 2: the trip between versions (counts are DiffGen's, from Mojang's jars) ---------- */
const VERSIONS = ["26.1.2", "26.2", "26.3"];
const HOPS = {
  "26.1.2>26.2": { classes: 188, methods: 89, fields: 23 },
  "26.2>26.3": { classes: 215, methods: 171, fields: 36 },
  "26.3>26.2": { classes: 222, methods: 228, fields: 31 },
  "26.2>26.1.2": { classes: 189, methods: 102, fields: 24 },
};

function demoPath(host) {
  const pick = (label, role, value) => `<div class="pick" role="group" aria-label="${label}"><span>${label}</span>${VERSIONS.map((v) => `<button class="btn btn-ghost btn-sm" type="button" data-role="${role}" data-v="${v}" aria-pressed="${v === value}">${v}</button>`).join("")}</div>`;
  const demo = buildDemo(host, {
    title: "The trip",
    sub: "Pick the version a mod was built for and the one you play. Isotope steps through each release in between, forwards or backwards.",
    controls: pick("Built for", "from", "26.1.2") + pick("Running on", "to", "26.3"),
    stats: [["hops", "Steps"], ["classes", "Class rules"], ["members", "Method + field rules"]],
  });
  let from = "26.1.2", to = "26.3";
  let travel = 0;
  const sync = () => {
    host.querySelectorAll('[data-role="from"]').forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.v === from)));
    host.querySelectorAll('[data-role="to"]').forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.v === to)));
    travel = 0;
  };
  host.querySelectorAll('[data-role="from"]').forEach((b) => b.addEventListener("click", () => { from = b.dataset.v; sync(); }));
  host.querySelectorAll('[data-role="to"]').forEach((b) => b.addEventListener("click", () => { to = b.dataset.v; sync(); }));

  runWhileVisible(host, (dt, t) => {
    const { ctx, w, h } = demo.view;
    ctx.clearRect(0, 0, w, h);
    const a = VERSIONS.indexOf(from), b = VERSIONS.indexOf(to);
    const step = a < b ? 1 : -1;
    const hops = [];
    for (let i = a; i !== b; i += step) hops.push(`${VERSIONS[i]}>${VERSIONS[i + step]}`);
    const xs = VERSIONS.map((_, i) => w * (0.16 + i * 0.34));
    const y = h * 0.5;
    travel = Math.min(hops.length || 0, travel + dt * (reduceMotion ? 10 : 0.8));

    ctx.strokeStyle = palette.lineStrong;
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(xs[0], y);
    ctx.lineTo(xs[2], y);
    ctx.stroke();

    // Active path.
    if (hops.length) {
      const start = xs[a];
      const head = lerp(start, xs[b], travel / hops.length);
      ctx.strokeStyle = palette.accent;
      ctx.lineWidth = 4;
      ctx.beginPath();
      ctx.moveTo(start, y);
      ctx.lineTo(head, y);
      ctx.stroke();
      ctx.fillStyle = palette.accent2;
      ctx.beginPath();
      ctx.arc(head, y, 7 + Math.sin(t * 6) * 1.5, 0, Math.PI * 2);
      ctx.fill();
    }

    VERSIONS.forEach((v, i) => {
      const isEnd = i === a || i === b;
      const passed = hops.length && (step > 0 ? i >= a && i <= b : i <= a && i >= b);
      ctx.fillStyle = isEnd ? palette.accent : passed ? alpha(palette.accent, 0.5) : palette.surface;
      ctx.strokeStyle = isEnd || passed ? palette.accent : palette.lineStrong;
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(xs[i], y, isEnd ? 16 : 12, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
      ctx.fillStyle = palette.ink;
      ctx.font = `700 ${w < 520 ? 13 : 16}px Manrope, sans-serif`;
      ctx.textAlign = "center";
      ctx.fillText(v, xs[i], y - 30);
      ctx.font = `500 11px "DM Mono", monospace`;
      ctx.fillStyle = palette.muted;
      ctx.fillText(i === a ? "BUILT FOR" : i === b ? "RUNNING ON" : "", xs[i], y + 38);
    });

    // Per-hop rule counts under each segment.
    hops.forEach((key, k) => {
      const [x1, x2] = [xs[VERSIONS.indexOf(key.split(">")[0])], xs[VERSIONS.indexOf(key.split(">")[1])]];
      ctx.globalAlpha = clamp(travel - k, 0, 1);
      ctx.fillStyle = palette.accent;
      ctx.font = `500 11px "DM Mono", monospace`;
      const c = HOPS[key];
      ctx.fillText(`${c.classes + c.methods + c.fields} rules`, (x1 + x2) / 2, y - 12);
      ctx.globalAlpha = 1;
    });
    ctx.textAlign = "left";

    if (!hops.length) {
      ctx.fillStyle = palette.muted;
      ctx.font = `500 13px "DM Sans", sans-serif`;
      ctx.textAlign = "center";
      ctx.fillText("Same version: the mod loads normally, no Isotope needed.", w / 2, h * 0.86);
      ctx.textAlign = "left";
    }

    const sum = hops.reduce((acc, key) => ({ classes: acc.classes + HOPS[key].classes, members: acc.members + HOPS[key].methods + HOPS[key].fields }), { classes: 0, members: 0 });
    demo.stat("hops", String(hops.length));
    demo.stat("classes", String(sum.classes));
    demo.stat("members", String(sum.members));
  });
}

/* ---------- Demo 3: the pre-flight check, on the twelve real mods we tested ---------- */
const MODS = [
  { name: "Too Fast", redirected: 0, unresolved: 0, verdict: "loaded" },
  { name: "Fishing Real", redirected: 3, unresolved: 1, verdict: "loaded" },
  { name: "Nyf's Spiders", redirected: 7, unresolved: 12, verdict: "loaded" },
  { name: "Patchouli", redirected: 79, unresolved: 26, verdict: "loaded" },
  { name: "Carved Wood", redirected: 33, unresolved: 10, verdict: "startup" },
  { name: "Thermoo", redirected: 174, unresolved: 30, verdict: "startup" },
  { name: "Artifacts", redirected: 25, unresolved: 46, verdict: "shape" },
  { name: "YUNG's API", redirected: 19, unresolved: 47, verdict: "shape" },
  { name: "Better Dungeons", redirected: 1, unresolved: 38, verdict: "shape" },
  { name: "Frostiful", redirected: 256, unresolved: 95, verdict: "shape" },
  { name: "Paxi", redirected: 0, unresolved: 0, verdict: "dependency" },
  { name: "Fabric Waystones", redirected: 29, unresolved: 5, verdict: "untested" },
];
const VERDICTS = {
  loaded: ["LOADED", "good"],
  startup: ["WOULD CRASH AT STARTUP", "warn"],
  shape: ["CLASS CHANGED SHAPE", "bad"],
  dependency: ["NEEDS A HELD-BACK MOD", "faint"],
  untested: ["NOT TESTED", "faint"],
};

function demoPreflight(host) {
  const demo = buildDemo(host, {
    title: "The pre-flight check",
    sub: "Twelve real mods whose newest build is for 26.1.x, on a 26.3 server. Switch the safety check off to see what happens without it.",
    tall: true,
    controls: switchHtml("Safety check", true) + buttonHtml("Run again", "again"),
    stats: [["loaded", "Loaded"], ["held", "Held back"], ["server", "Server"]],
  });
  const toggle = demo.q('[data-role="toggle"]');
  let scan = 0;
  demo.q('[data-role="again"]').addEventListener("click", () => { scan = 0; });
  toggle.addEventListener("change", () => { scan = 0; });

  runWhileVisible(host, (dt, t) => {
    const { ctx, w, h } = demo.view;
    const safe = toggle.checked;
    scan = Math.min(MODS.length + 1, scan + dt * (reduceMotion ? 40 : 3.2));
    ctx.clearRect(0, 0, w, h);
    const rowH = (h - 16) / MODS.length;
    const narrow = w < 560;
    let loaded = 0, held = 0, crash = false;

    MODS.forEach((mod, i) => {
      const y = 8 + i * rowH;
      const done = scan > i + 1;
      const active = scan > i && !done;
      let [label, tone] = VERDICTS[mod.verdict];
      if (!safe && (mod.verdict === "startup" || mod.verdict === "shape")) { label = "LOADED → CRASHES"; tone = "bad"; }
      if (done) {
        if (label.startsWith("LOADED")) loaded++;
        else if (mod.verdict !== "untested") held++;
        if (!safe && (mod.verdict === "startup" || mod.verdict === "shape")) crash = true;
      }
      roundRect(ctx, 8, y + 2, w - 16, rowH - 4, 8);
      ctx.fillStyle = active ? alpha(palette.accent, 0.12) : done ? alpha(palette[tone], 0.07) : alpha(palette.ink, palette.light ? 0.025 : 0.035);
      ctx.fill();
      const mid = y + rowH / 2 + 4;
      ctx.fillStyle = done ? palette.ink : palette.muted;
      ctx.font = `600 ${narrow ? 12 : 14}px "DM Sans", sans-serif`;
      ctx.fillText(mod.name, 20, mid);
      if (!narrow) {
        ctx.font = `500 11px "DM Mono", monospace`;
        ctx.fillStyle = palette.muted;
        ctx.fillText(`${mod.redirected} redirected · ${mod.unresolved} left over`, w * 0.33, mid);
      }
      if (done) {
        ctx.font = `600 ${narrow ? 9.5 : 11}px "DM Mono", monospace`;
        const tw = ctx.measureText(label).width + 16;
        roundRect(ctx, w - 18 - tw, y + rowH / 2 - 10, tw, 20, 10);
        ctx.fillStyle = alpha(palette[tone], 0.16);
        ctx.fill();
        ctx.fillStyle = palette[tone];
        ctx.fillText(label, w - 10 - tw, y + rowH / 2 + 4);
      } else if (active) {
        ctx.fillStyle = palette.accent;
        ctx.fillRect(w - 80, y + rowH / 2 - 1, 60 * ((t * 2) % 1), 2);
      }
    });

    const finished = scan > MODS.length;
    demo.stat("loaded", String(loaded), loaded ? "good" : "");
    demo.stat("held", String(held), held ? "warn" : "");
    demo.stat("server", finished ? (crash ? "Crashed" : "Done") : "Starting…", finished ? (crash ? "bad" : "good") : "");
  });
}

const demos = { rewrite: demoRewrite, path: demoPath, preflight: demoPreflight };
document.querySelectorAll("[data-demo]").forEach((host) => {
  const build = demos[host.dataset.demo];
  if (build) build(host);
});
document.querySelectorAll("[data-hero]").forEach(heroScene);

})();

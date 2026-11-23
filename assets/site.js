/* Isotope website: theme toggle, header, mobile menu, FAQ, the pinned launch stage and
   the first-visit preloader. The theme and preloader flags are set early by each page's head. */
(() => {
  const $ = (id) => document.getElementById(id);
  const root = document.documentElement;
  const calm = () => matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* theme toggle, remembered per viewer */
  const themeBtn = $("theme");
  if (themeBtn) themeBtn.addEventListener("click", () => {
    const dark = root.dataset.theme ? root.dataset.theme === "dark" : matchMedia("(prefers-color-scheme: dark)").matches;
    const next = dark ? "light" : "dark";
    const go = () => { root.dataset.theme = next; };
    document.startViewTransition && !calm() ? document.startViewTransition(go) : go();
    try { localStorage.setItem("iso-theme", next); } catch (e) {}
  });

  /* header: solid after a little scroll, always visible */
  const hdr = $("hdr");
  const menu = $("menu");
  const setMenu = (open) => {
    if (!hdr || !menu) return;
    hdr.classList.toggle("open", open);
    menu.setAttribute("aria-expanded", String(open));
    menu.setAttribute("aria-label", open ? "Close menu" : "Open menu");
  };
  if (menu) {
    menu.addEventListener("click", () => setMenu(!hdr.classList.contains("open")));
    addEventListener("keydown", (e) => { if (e.key === "Escape") setMenu(false); });
    addEventListener("resize", () => { if (innerWidth > 880) setMenu(false); });
  }

  /* FAQ: slide each answer open and closed instead of snapping */
  document.querySelectorAll(".faq details").forEach((d) => {
    const ans = d.querySelector(":scope > :not(summary)");
    let anim = null;
    d.querySelector("summary").addEventListener("click", (e) => {
      if (!ans || calm()) return;
      e.preventDefault();
      const opening = !d.open || d.classList.contains("closing");
      const from = d.open ? ans.offsetHeight : 0;
      if (anim) anim.cancel();
      d.open = true;
      d.classList.toggle("closing", !opening);
      const to = opening ? ans.scrollHeight : 0;
      anim = ans.animate([{ height: from + "px", opacity: opening ? 0 : 1 }, { height: to + "px", opacity: opening ? 1 : 0 }],
        { duration: opening ? 380 : 280, easing: "cubic-bezier(0.2, 0.7, 0.1, 1)" });
      anim.onfinish = () => { anim = null; if (!opening) { d.open = false; d.classList.remove("closing"); } };
    });
  });

  /* pinned launch stage: steps light up as you scroll through the section */
  const sec = $("launch");
  const steps = [...document.querySelectorAll("#steps li")];
  const layers = [...document.querySelectorAll("#tstage .layer")];
  const lbl = $("tlbl");
  let cur = -1;
  function setStep(n) {
    if (n === cur) return; cur = n;
    steps.forEach((s, i) => s.classList.toggle("on", i === n));
    layers.forEach((l, i) => l.classList.toggle("on", i === n));
    if (lbl && steps[n]) lbl.textContent = steps[n].dataset.label;
  }
  function onTear() {
    if (!sec || !steps.length) return;
    if (innerWidth <= 900) { setStep(0); return; }
    const r = sec.getBoundingClientRect(); const max = sec.offsetHeight - innerHeight;
    const p = Math.min(1, Math.max(0, -r.top / max));
    $("bar").style.width = (p * 100) + "%";
    setStep(Math.min(steps.length - 1, Math.floor(p * steps.length)));
  }
  steps.forEach((s, i) => s.addEventListener("click", () => {
    if (innerWidth <= 900) return;
    const max = sec.offsetHeight - innerHeight;
    scrollTo({ top: sec.offsetTop + max * (i + 0.5) / steps.length, behavior: calm() ? "auto" : "smooth" });
  }));

  function onScroll() {
    if (hdr) hdr.classList.toggle("solid", scrollY > 10);
    onTear();
  }
  addEventListener("scroll", onScroll, { passive: true });
  addEventListener("resize", onTear);
  onScroll(); if (steps.length) setStep(0);

  /* first-visit preloader: the logo fills in, then the page fades up */
  if (root.classList.contains("preload")) {
    let done = false;
    const finish = () => { if (done) return; done = true; root.classList.add("pre-done"); setTimeout(() => root.classList.remove("preload", "pre-done"), 650); };
    const go = () => setTimeout(finish, Math.max(0, 1350 - performance.now()));
    document.readyState === "complete" ? go() : addEventListener("load", go);
    setTimeout(finish, 4000);
  }
})();

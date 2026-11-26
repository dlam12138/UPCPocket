(() => {
  const root = document.documentElement;
  const themeColor = document.querySelector('meta[name="theme-color"]');
  const systemTheme = matchMedia("(prefers-color-scheme: dark)");
  const preferenceKey = "upc-pocket:theme";
  const choices = ["dark", "light", "system"];
  let preference = "dark";

  try {
    const saved = localStorage.getItem(preferenceKey);
    if (choices.includes(saved)) preference = saved;
  } catch {
    // The default appearance is available before storage is.
  }

  function applyTheme() {
    const theme = preference === "system" ? (systemTheme.matches ? "dark" : "light") : preference;
    themeColor.content = theme === "dark" ? "#0a0a14" : "#fff6f8";
    if (root.dataset.theme === theme) return;
    root.dataset.theme = theme;
    root.dispatchEvent(new Event("pocket-theme-change"));
  }

  applyTheme();
  systemTheme.addEventListener("change", applyTheme);
  document.addEventListener("DOMContentLoaded", () => {
    const select = document.getElementById("appearance-theme");
    const status = document.getElementById("appearance-theme-status");
    select.value = preference;
    select.addEventListener("change", () => {
      preference = select.value;
      applyTheme();
      try {
        localStorage.setItem(preferenceKey, preference);
        status.textContent = "";
      } catch {
        status.textContent = "主题仅在本次打开时生效。";
      }
    });
  }, { once: true });
})();

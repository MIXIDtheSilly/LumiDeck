// LumiDeck's window. Sends the app commands such as "open" or "set dpi 200", and shows
// the state it sends back (src/main.rs).

const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;
const appWindow = window.__TAURI__.window.getCurrentWindow();

const RESOLUTIONS = [[1280, 720], [1600, 900], [1920, 1080], [2560, 1440], [3840, 2160]];
const SCALES = [[160, "100%"], [200, "125%"], [240, "150%"], [280, "175%"], [320, "200%"]];
const CHECK = '<svg class="icon" viewBox="0 0 24 24"><path d="M6 12.5l4 4 8-9"/></svg>';
const $ = id => document.getElementById(id);
const send = message => invoke("page", { message });
let state = null;

// This screen's size in real pixels, offered as a resolution.
const screenSize = [Math.round(screen.width * devicePixelRatio) & ~1, Math.round(screen.height * devicePixelRatio) & ~1];
const resolutions = () => {
  const list = RESOLUTIONS.slice();
  if (!list.some(([w, h]) => w === screenSize[0] && h === screenSize[1])) list.push(screenSize);
  return list.sort((a, b) => a[0] * a[1] - b[0] * b[1]);
};
const resolutionLabel = r => r.replace("x", " × ");
const scaleLabel = dpi => (SCALES.find(([d]) => d === dpi) || [dpi, Math.round(dpi / 1.6) + "%"])[1];

function batteryIcon(level) {
  const width = Math.max(1.5, 16 * level / 100);
  return `<svg viewBox="0 0 24 14"><rect x="1" y="1" width="19" height="12" rx="3" fill="none" stroke="currentColor" stroke-width="1.4"/>` +
    `<rect x="3.5" y="3.5" width="${width.toFixed(1)}" height="7" rx="1.2" fill="currentColor"/><rect x="21" y="4.5" width="2" height="5" rx="1" fill="currentColor"/></svg>`;
}

function render(next) {
  state = next;
  const { device, session, settings } = state;
  $("status").hidden = !session;
  $("device").textContent = device ? device.name : "Plug in your device";

  const meta = $("meta");
  meta.innerHTML = "";
  if (device) {
    if (state.battery != null) {
      const battery = document.createElement("span");
      battery.className = "battery";
      battery.innerHTML = batteryIcon(state.battery) + `<span>${state.battery}%${state.charging ? " · Charging" : ""}</span>`;
      meta.append(battery);
    }
    const serial = document.createElement("span");
    serial.textContent = device.serial;
    meta.append(serial);
  } else {
    meta.textContent = "Connect it with a USB cable and turn on USB debugging (Settings › Developer options).";
  }
  $("error").hidden = !state.error;
  $("error").textContent = state.error || "";

  const open = $("open");
  open.disabled = !device && !session;
  open.textContent = session ? "Show desktop" : "Open desktop";
  $("close").hidden = !session;
  $("later").hidden = !session;

  document.querySelector('[data-menu="resolution"] span').textContent = resolutionLabel(settings.resolution);
  document.querySelector('[data-menu="dpi"] span').textContent = scaleLabel(settings.dpi);
  for (const item of document.querySelectorAll("[data-toggle]")) {
    item.querySelector(".switch").classList.toggle("on", !!settings[item.dataset.toggle]);
  }

  $("usb-ready").hidden = state.usb !== "ready";
  const setup = $("usb-setup");
  setup.hidden = state.usb === "ready";
  setup.disabled = state.usb === "busy" || !device || session;
  setup.textContent = state.usb === "busy" ? "Setting up…" : "Set up";
  $("usb-sub").textContent = state.usb === "ready"
    ? "This PC talks to the device straight over USB."
    : session ? "Faster than ADB. Close the desktop to set it up."
    : device ? "Faster than ADB. Windows asks for an administrator once."
    : "Faster than ADB. Plug the device in to set it up.";
  $("footer").textContent = `LumiDeck ${state.version}`;
}

$("open").onclick = () => send(state.session ? "show" : "open");
$("close").onclick = () => send("close");
$("usb-setup").onclick = () => send("setupUsb");
for (const item of document.querySelectorAll("[data-toggle]")) {
  item.onclick = () => {
    const key = item.dataset.toggle;
    state.settings[key] = !state.settings[key];
    render(state);
    send(`set ${key} ${state.settings[key] ? 1 : 0}`);
  };
}

// The title bar's buttons; the bar itself drags the window (data-tauri-drag-region).
$("minimize").onclick = () => appWindow.minimize();
$("maximize").onclick = () => appWindow.toggleMaximize();
$("close-window").onclick = () => appWindow.close();
const syncMaximized = async () => document.body.classList.toggle("maximized", await appWindow.isMaximized());
appWindow.onResized(syncMaximized);
syncMaximized();
appWindow.onFocusChanged(({ payload: focused }) => document.body.classList.toggle("inactive", !focused));

// Drop-down menus
const menu = $("menu");
let menuButton = null;
function closeMenu() {
  menu.classList.remove("shown");
  menuButton?.classList.remove("open");
  menuButton = null;
}
function showMenu(button) {
  const kind = button.dataset.menu;
  const options = kind === "resolution"
    ? resolutions().map(([w, h]) => {
        const value = `${w}x${h}`;
        const here = w === screenSize[0] && h === screenSize[1];
        return [value, resolutionLabel(value), here ? "This screen" : ""];
      })
    : SCALES.map(([dpi, label]) => [String(dpi), label, dpi === 160 ? "Default" : ""]);
  const current = String(state.settings[kind]);
  menu.innerHTML = "";
  for (const [value, label, extra] of options) {
    const option = document.createElement("button");
    option.className = "option" + (value === current ? " chosen" : "");
    option.innerHTML = `${CHECK}<span>${label}</span>` + (extra ? `<span class="extra">${extra}</span>` : "");
    option.onclick = () => {
      state.settings[kind] = kind === "dpi" ? Number(value) : value;
      render(state);
      send(`set ${kind} ${value}`);
      closeMenu();
    };
    menu.append(option);
  }
  const rect = button.getBoundingClientRect();
  menu.style.right = `${document.documentElement.clientWidth - rect.right}px`;
  menu.style.top = `${rect.bottom + 6}px`;
  const room = window.innerHeight - rect.bottom - 16;
  if (menu.offsetHeight > room) menu.style.top = `${Math.max(8, rect.top - 6 - menu.offsetHeight)}px`;
  menuButton = button;
  button.classList.add("open");
  requestAnimationFrame(() => menu.classList.add("shown"));
}
for (const button of document.querySelectorAll("[data-menu]")) {
  button.onclick = event => {
    event.stopPropagation();
    const same = menuButton === button;
    closeMenu();
    if (!same) showMenu(button);
  };
}
document.addEventListener("click", event => { if (!menu.contains(event.target)) closeMenu(); });
document.addEventListener("keydown", event => { if (event.key === "Escape") closeMenu(); });
window.addEventListener("resize", closeMenu);
document.addEventListener("scroll", closeMenu, true);
document.addEventListener("contextmenu", event => event.preventDefault());

listen("state", event => render(event.payload)).then(() => send("ready"));

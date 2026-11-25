(function () {
  "use strict";

  const CLIENTS_POLL_MS = 3000;
  // A client mid-startup turns Active with no result event, so only a poll reveals it.
  const CLIENTS_STARTING_POLL_MS = 1000;
  const CLIENTS_BACKGROUND_POLL_MS = 15000;
  const MAX_LOG_ENTRIES = 500;
  // A discovery sweep can take seconds; without this, polls pile up faster than they drain.
  const CLIENTS_REQUEST_TIMEOUT_MS = 15000;
  // Backstop for a result that never arrives. Generous: an inject can sit on a sudo prompt.
  const BUSY_TIMEOUT_MS = 120000;
  const COLLAPSED_KEY = "projectx.collapsedAccounts";

  const state = {
    config: null,
    sessions: [],
    characters: {},
    launchingAccountId: null,
    view: "play",
    collapsed: loadSet(COLLAPSED_KEY),
    clients: null,
    clientsPollTimer: null,
    clientsPollMs: 0,
    clientsRequestAt: 0,
    busyPids: {},
    renderedClients: null,
    launcherStatus: null,
    store: null,
    storeScripts: null,
    storeError: null,
    busyStore: {},
    signInDismissed: false,
    schedulerRunning: false,
    schedulerStatus: null,
    heroTarget: null,
  };
  const schedulerChosen = new Set();

  const $ = (s) => document.querySelector(s);
  const $$ = (s) => [...document.querySelectorAll(s)];

  const views = {
    play: $("#play-view"),
    clients: $("#clients-view"),
    scheduler: $("#scheduler-view"),
    store: $("#store-view"),
    logs: $("#logs-view"),
    settings: $("#settings-view"),
  };
  const navItems = $$(".nav-link[data-view]");
  const content = $("#content");
  const railQuery = window.matchMedia("(min-width: 900px)");

  const topbarStats = $("#topbar-stats");
  const sidebarVersion = $("#sidebar-version");
  const clientStatus = $("#client-status");
  const statusbar = $("#statusbar");

  const heroGreeting = $("#hero-greeting");
  const heroSubtitle = $("#hero-subtitle");
  const btnHeroPlay = $("#btn-hero-play");
  const heroPlayLabel = $("#hero-play-label");

  const accountsList = $("#accounts-list");
  const accountsEmpty = $("#accounts-empty");
  const btnLogin = $("#btn-login");
  const btnExpandAll = $("#btn-expand-all");
  const btnSelectAll = $("#btn-select-all");

  const launchLabel = $("#launch-label");
  const launchSub = $("#launch-sub");
  const launchStatus = $("#launch-status");
  const launchGap = $("#launch-gap");
  const btnLaunch = $("#btn-launch");
  const schedDelay = $("#sched-delay");

  const clientsList = $("#clients-list");
  const clientsEmpty = $("#clients-empty");
  const runningList = $("#running-list");
  const runningEmpty = $("#running-empty");
  const navClientsCount = $("#nav-clients-count");
  const btnRefreshClients = $("#btn-refresh-clients");
  const btnSideRefresh = $("#btn-side-refresh");

  const launcherUpdate = $("#launcher-update");
  const launcherUpdateDetail = $("#launcher-update-detail");
  const btnLauncherUpdate = $("#btn-launcher-update");

  const userName = $("#user-name");
  const userSub = $("#user-sub");
  const btnUserSignIn = $("#btn-user-sign-in");
  const btnUserSignOut = $("#btn-user-sign-out");

  const storeAccount = $("#store-account");
  const storeOrigin = $("#store-origin");
  const storePairing = $("#store-pairing");
  const storeCode = $("#store-code");
  const storePairingLink = $("#store-pairing-link");
  const storeError = $("#store-error");
  const storeList = $("#store-list");
  const storeEmpty = $("#store-empty");
  const btnStoreSignIn = $("#btn-store-sign-in");
  const btnStoreSignOut = $("#btn-store-sign-out");
  const btnStoreRefresh = $("#btn-store-refresh");
  const btnStorePage = $("#btn-store-page");

  const signInDialog = $("#sign-in-dialog");
  const signInGo = $("#sign-in-go");
  const signInWaiting = $("#sign-in-waiting");
  const signInError = $("#sign-in-error");

  const statusLog = $("#status-log");
  const btnClearLogs = $("#btn-clear-logs");

  const chkCloseAfter = $("#chk-close-after");
  const chkAutoInject = $("#chk-auto-inject");
  const chkDebugLogging = $("#chk-debug-logging");
  const inpLaunchCmd = $("#inp-launch-cmd");
  const btnSaveSettings = $("#btn-save-settings");
  const btnResetSettings = $("#btn-reset-settings");

  function send(msg) {
    window.ipc.postMessage(JSON.stringify(msg));
  }

  window.__projectx_callback = function (event) {
    switch (event.type) {
      case "init":
        state.config = event.config;
        state.sessions = event.sessions;
        restoreConfigToUI();
        renderAccounts();
        logStatus("Launcher ready");
        send({ type: "launcher_status" });
        requestClients(true);
        startClientsPolling();
        break;

      case "sessions_updated":
        state.sessions = event.sessions;
        renderAccounts();
        break;

      case "login_complete":
        state.sessions.push(event.session);
        state.collapsed.delete(event.session.user_id);
        saveSet(COLLAPSED_KEY, state.collapsed);
        renderAccounts();
        logStatus("Logged in as " + event.session.display_name, "success");
        break;

      case "login_error":
        logStatus(event.message, "error");
        break;

      case "logout_complete":
        state.sessions = state.sessions.filter((s) => s.user_id !== event.user_id);
        state.collapsed.delete(event.user_id);
        saveSet(COLLAPSED_KEY, state.collapsed);
        renderAccounts();
        logStatus("Logged out");
        break;

      case "character_info":
        state.characters[event.account_id] = event;
        paintKnownFaces(event);
        break;

      case "scheduler_status":
        onSchedulerStatus(event);
        break;

      case "launch_status":
        logStatus(event.message);
        break;

      case "launch_complete":
        state.launchingAccountId = null;
        renderAccounts();
        logStatus("Game launched", "success");
        requestClients(true);
        break;

      case "launch_error":
        state.launchingAccountId = null;
        renderAccounts();
        logStatus(event.message, "error");
        break;

      case "config_saved":
        logStatus("Settings saved", "success");
        break;

      case "clients_list":
        state.clientsRequestAt = 0;
        state.clients = event.clients || [];
        pruneBusyPids();
        renderClients();
        renderStats();
        startClientsPolling();
        break;

      case "launcher_status":
        state.launcherStatus = event.status;
        renderVersion();
        renderLauncherUpdate();
        break;

      case "store_status":
        if (event.signed_in && state.store && !state.store.signed_in) {
          logStatus("Signed in to the store as " + (event.name || "your account"), "success");
        }
        state.store = event;
        if (event.error) logStatus(event.error, "error");
        renderStore();
        renderUserCard();
        renderHero();
        renderSignInDialog();
        break;

      case "script_settings":
        onScriptSettings(event);
        break;

      case "store_scripts":
        state.storeScripts = event.scripts || [];
        state.storeError = event.error || null;
        if (state.view === "scheduler") fillScriptNames();
        renderStore();
        break;

      case "store_result":
        delete state.busyStore[event.internal_name];
        logStatus(event.message, event.ok ? "success" : "error");
        renderStore();
        break;

      case "client_control_result":
        delete state.busyPids[event.pid];
        logStatus("pid " + event.pid + ": " + event.message, event.ok ? "success" : "error");
        state.renderedClients = null;
        renderClients();
        break;
    }
  };

  // ---- Shared helpers ----

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function icon(id, className) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("class", className || "btn-icon");
    const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
    use.setAttribute("href", "#" + id);
    svg.appendChild(use);
    return svg;
  }

  function button(label, variant, disabled, onClick) {
    const b = el("button", "btn btn-small" + (variant ? " " + variant : ""), label);
    b.type = "button";
    b.disabled = !!disabled;
    b.addEventListener("click", (e) => {
      e.stopPropagation();
      onClick();
    });
    return b;
  }

  const button_ = (...args) => button(...args);

  function badge(label, cls) {
    return el("span", "badge" + (cls ? " badge-" + cls : ""), label);
  }

  function plural(n, word) {
    return n + " " + word + (n === 1 ? "" : "s");
  }

  function formatSize(bytes) {
    if (!bytes) return null;
    const mb = bytes / (1024 * 1024);
    return mb >= 1 ? mb.toFixed(1) + " MB" : Math.max(1, Math.round(bytes / 1024)) + " KB";
  }

  function formatClock(date, withSeconds) {
    const parts = [date.getHours(), date.getMinutes()];
    if (withSeconds) parts.push(date.getSeconds());
    return parts.map((n) => String(n).padStart(2, "0")).join(":");
  }

  function greeting() {
    const hour = new Date().getHours();
    return hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";
  }

  // Storage can be unavailable in the webview; collapsing is only a convenience.
  function loadSet(key) {
    try {
      return new Set(JSON.parse(localStorage.getItem(key) || "[]"));
    } catch (_) {
      return new Set();
    }
  }

  function saveSet(key, set) {
    try {
      localStorage.setItem(key, JSON.stringify([...set]));
    } catch (_) {
      // ignored
    }
  }

  /** The chathead when RuneScape has one, otherwise the character's initial. */
  function paintFace(portrait, displayName, known) {
    portrait.textContent = "";
    if (known && known.avatar) {
      const img = document.createElement("img");
      img.src = known.avatar;
      img.alt = "";
      portrait.appendChild(img);
      portrait.classList.remove("is-blank");
      return;
    }
    portrait.textContent = (displayName || "?").charAt(0).toUpperCase();
    portrait.classList.add("is-blank");
  }

  function characterMeta(known) {
    const parts = [];
    if (known && known.total) parts.push("Total " + known.total.toLocaleString("en-GB"));
    if (known && known.rank) parts.push("Rank " + known.rank.toLocaleString("en-GB"));
    return parts.join(" · ");
  }

  function paintKnownFaces(event) {
    $$(".char-face").forEach((portrait) => {
      if (portrait.dataset.accountId !== event.account_id) return;
      const card = portrait.closest(".account-card");
      const name = card && card.querySelector(".card-name");
      paintFace(portrait, name && name.textContent, event);
      const meta = card && card.querySelector(".card-meta");
      if (meta) meta.textContent = characterMeta(event);
    });
  }

  // ---- Scheduler ----

  function everyCharacter() {
    return state.sessions.flatMap((session) =>
      session.accounts
        .filter((account) => account.displayName)
        .map((account) => ({
          account_id: account.accountId,
          display_name: account.displayName,
          session_name: session.display_name,
        })),
    );
  }

  function chosenCharacters() {
    return everyCharacter().filter((c) => schedulerChosen.has(c.account_id));
  }

  function renderScheduler() {
    const list = $("#scheduler-list");
    const empty = $("#scheduler-empty");
    const characters = everyCharacter();
    list.textContent = "";
    empty.hidden = characters.length > 0;

    characters.forEach((character) => {
      const row = el("label", "toggle-row");
      const text = el("span", "toggle-text");
      const own = characterPlans()[character.account_id];
      text.append(
        el("span", "toggle-title", character.display_name),
        el("small", "toggle-note", character.session_name || ""),
        el("small", "toggle-note plan-summary", own ? planSummary(own) : "No script plan"),
      );
      const picker = button(own ? "Edit plan" : "Set up plan", "", false, () => openPlanEditor(character));
      const tick = el("input", "switch");
      tick.type = "checkbox";
      tick.checked = schedulerChosen.has(character.account_id);
      tick.disabled = state.schedulerRunning;
      tick.addEventListener("change", () => {
        if (tick.checked) schedulerChosen.add(character.account_id);
        else schedulerChosen.delete(character.account_id);
        renderAccounts();
      });
      row.append(text, picker, tick);
      list.appendChild(row);
    });
  }

  // ---- Script plans (one per character) ----

  let editing = null;

  function characterPlans() {
    if (!state.config) return {};
    if (!state.config.character_plans) state.config.character_plans = {};
    return state.config.character_plans;
  }

  function nameOf(accountId) {
    const character = everyCharacter().find((c) => c.account_id === accountId);
    return character ? character.display_name : "Character";
  }

  function saveConfig() {
    if (state.config) send({ type: "save_config", config: state.config });
  }

  function planSummary(plan) {
    const steps = plan.steps.map(
      (st) => st.script + " " + st.min_minutes + (st.max_minutes !== st.min_minutes ? "-" + st.max_minutes : "") + " min",
    );
    return steps.join(" › ") + (plan.repeat ? " · repeats" : "");
  }

  function fillScriptNames() {
    const names = new Set();
    (state.storeScripts || []).forEach((sc) => {
      if (sc.owned || sc.free) names.add(sc.name);
    });
    Object.values(characterPlans()).forEach((plan) => plan.steps.forEach((st) => names.add(st.script)));
    const datalist = $("#script-names");
    datalist.textContent = "";
    [...names].sort().forEach((name) => datalist.appendChild(new Option(name)));
    if (!state.storeScripts && !state.storeScriptsAsked) {
      state.storeScriptsAsked = true;
      send({ type: "store_scripts" });
    }
  }

  function numberInput(value, title) {
    const input = el("input", "input input-small");
    input.type = "number";
    input.min = "1";
    input.max = "1440";
    input.value = value;
    input.title = title;
    return input;
  }

  // Script name (lower case) -> what to do with its settings when a client describes them.
  const settingsWanted = {};

  function settingsLabel(row) {
    const n = Object.keys(row.settings).length;
    return n ? "Settings (" + n + ")" : "Settings";
  }

  function settingInput(field, row, refresh) {
    const current = Object.prototype.hasOwnProperty.call(row.settings, field.key) ? row.settings[field.key] : field.value;
    const set = (value) => {
      row.settings[field.key] = value;
      refresh();
    };
    if (field.kind === "bool") {
      const box = el("input", "switch");
      box.type = "checkbox";
      box.checked = !!current;
      box.addEventListener("change", () => set(box.checked));
      return box;
    }
    if (field.kind === "int") {
      const input = el("input", "input input-small");
      input.type = "number";
      if (field.min > -1000000000) input.min = String(field.min);
      if (field.max < 1000000000) input.max = String(field.max);
      input.value = current ?? "";
      input.addEventListener("change", () => set(Math.round(Number(input.value) || 0)));
      return input;
    }
    if (field.kind === "choice") {
      const select = el("select", "input input-small plan-choice");
      (field.choices || []).forEach((choice) => select.appendChild(new Option(choice, choice)));
      select.value = current ?? "";
      select.addEventListener("change", () => set(select.value));
      return select;
    }
    const input = el("input", "input");
    input.type = "text";
    input.value = current ?? "";
    input.addEventListener("change", () => set(input.value));
    return input;
  }

  function drawSettings(panel, row, button, event) {
    panel.textContent = "";
    if (event.error) {
      panel.appendChild(el("p", "notice notice-error", event.error));
      return;
    }
    const fields = (event.fields || []).filter((f) => f.kind !== "other");
    if (!fields.length) {
      panel.appendChild(el("p", "muted", event.name + " has no settings to choose."));
      return;
    }
    const refresh = () => (button.textContent = settingsLabel(row));
    panel.appendChild(
      el("p", "muted plan-settings-note", "Used for " + event.name + " in this plan only. Anything left alone keeps your saved setting."),
    );
    fields.forEach((field) => {
      if (field.kind === "section") {
        panel.appendChild(el("span", "field-label plan-settings-section", field.name));
        return;
      }
      const line = el("label", "toggle-row plan-setting");
      const text = el("span", "toggle-text");
      text.append(el("span", "toggle-title", field.name));
      if (field.description) text.append(el("small", "toggle-note", field.description));
      line.append(text, settingInput(field, row, refresh));
      panel.appendChild(line);
    });
    const reset = button_("Use my saved settings", "plan-reset", false, () => {
      row.settings = {};
      refresh();
      drawSettings(panel, row, button, event);
    });
    panel.appendChild(reset);
  }

  function toggleSettings(row, script, button) {
    let panel = row.querySelector(".plan-settings");
    if (panel) {
      panel.remove();
      return;
    }
    panel = el("div", "plan-settings");
    row.appendChild(panel);
    const name = script.value.trim();
    if (!name) {
      panel.appendChild(el("p", "muted", "Pick the script first."));
      return;
    }
    const client = (state.clients || []).find((c) => c.state === "active");
    if (!client) {
      panel.appendChild(el("p", "muted", "Start a client with the engine loaded, then open this again to load the script's settings."));
      return;
    }
    panel.appendChild(el("p", "muted", "Loading " + name + "'s settings…"));
    settingsWanted[name.toLowerCase()] = (event) => drawSettings(panel, row, button, event);
    send({ type: "script_settings", pid: client.pid, script: name });
  }

  function onScriptSettings(event) {
    const key = (event.script || "").toLowerCase();
    const draw = settingsWanted[key];
    delete settingsWanted[key];
    if (draw) draw(event);
  }

  function stepRow(step) {
    const row = el("div", "plan-step");
    row.settings = Object.assign({}, step.settings || {});
    const script = el("input", "input plan-script");
    script.type = "text";
    script.placeholder = "Script name";
    script.setAttribute("list", "script-names");
    script.value = step.script || "";
    script.dataset.role = "script";
    const min = numberInput(step.min_minutes || 30, "Shortest run, in minutes");
    min.dataset.role = "min";
    const max = numberInput(step.max_minutes || 60, "Longest run, in minutes");
    max.dataset.role = "max";
    row.append(script, min, el("span", "muted", "to"), max, el("span", "muted", "min"));
    const up = button("↑", "plan-move", false, () => row.previousElementSibling && row.parentElement.insertBefore(row, row.previousElementSibling));
    up.title = "Run this script earlier";
    const down = button("↓", "plan-move", false, () => row.nextElementSibling && row.parentElement.insertBefore(row.nextElementSibling, row));
    down.title = "Run this script later";
    const settings = button(settingsLabel(row), "", false, () => toggleSettings(row, script, settings));
    settings.title = "Choose this script's settings for this plan";
    // A different script's settings do not carry over.
    script.addEventListener("change", () => {
      row.settings = {};
      settings.textContent = settingsLabel(row);
      const open = row.querySelector(".plan-settings");
      if (open) open.remove();
    });
    row.append(up, down, settings, button("Remove", "btn-danger", false, () => row.remove()));
    return row;
  }

  function showPlan(plan) {
    $("#plan-repeat").checked = !!plan.repeat;
    $("#plan-pause-min").value = plan.pause_min_seconds ?? 15;
    $("#plan-pause-max").value = plan.pause_max_seconds ?? 90;
    const steps = $("#plan-steps");
    steps.textContent = "";
    plan.steps.forEach((st) => steps.appendChild(stepRow(st)));
  }

  function openPlanEditor(character) {
    editing = character.account_id;
    const own = characterPlans()[editing];
    $("#plan-title").textContent = character.display_name + "'s script plan";
    showPlan(
      own || {
        steps: [{ script: "", min_minutes: 30, max_minutes: 60 }],
        repeat: true,
        pause_min_seconds: 15,
        pause_max_seconds: 90,
      },
    );
    const copy = $("#plan-copy");
    copy.textContent = "";
    copy.appendChild(new Option("Another character…", ""));
    Object.keys(characterPlans())
      .filter((id) => id !== editing)
      .forEach((id) => copy.appendChild(new Option(nameOf(id), id)));
    copy.parentElement.hidden = copy.options.length < 2;
    $("#btn-plan-delete").hidden = !own;
    $("#plan-error").hidden = true;
    $("#plan-card").hidden = false;
    fillScriptNames();
    $("#plan-card").scrollIntoView({ behavior: "smooth", block: "start" });
  }

  function closePlanEditor() {
    editing = null;
    $("#plan-card").hidden = true;
  }

  function copyPlan() {
    const from = characterPlans()[$("#plan-copy").value];
    if (from) showPlan(from);
    $("#plan-copy").value = "";
  }

  function planError(message) {
    const box = $("#plan-error");
    box.textContent = message;
    box.hidden = false;
  }

  function savePlan(e) {
    e.preventDefault();
    if (!editing) return;
    const steps = [...$("#plan-steps").children]
      .map((row) => {
        const step = {
          script: row.querySelector('[data-role="script"]').value.trim(),
          min_minutes: Number(row.querySelector('[data-role="min"]').value),
          max_minutes: Number(row.querySelector('[data-role="max"]').value),
        };
        if (Object.keys(row.settings || {}).length) step.settings = row.settings;
        return step;
      })
      .filter((st) => st.script);
    const pauseMin = Number($("#plan-pause-min").value);
    const pauseMax = Number($("#plan-pause-max").value);
    if (!steps.length) return planError("Add at least one script.");
    const bad = steps.find((st) => !(st.min_minutes >= 1 && st.max_minutes <= 1440 && st.min_minutes <= st.max_minutes));
    if (bad) return planError(bad.script + " needs a shortest run of at least 1 minute, no longer than its longest (up to 1440).");
    if (!(pauseMin >= 0 && pauseMax <= 600 && pauseMin <= pauseMax)) {
      return planError("The pause must be between 0 and 600 seconds, the shorter first.");
    }
    const name = nameOf(editing);
    characterPlans()[editing] = {
      name,
      steps,
      repeat: $("#plan-repeat").checked,
      pause_min_seconds: pauseMin,
      pause_max_seconds: pauseMax,
    };
    saveConfig();
    logStatus("Script plan for " + name + " saved", "success");
    closePlanEditor();
    renderScheduler();
  }

  function deletePlan() {
    if (!editing) return;
    const name = nameOf(editing);
    delete characterPlans()[editing];
    saveConfig();
    logStatus("Script plan for " + name + " removed");
    closePlanEditor();
    renderScheduler();
  }

  function onSchedulerStatus(event) {
    const flipped = state.schedulerRunning !== event.running;
    state.schedulerRunning = event.running;
    state.schedulerStatus = event;
    const box = $("#scheduler-status");
    box.hidden = false;
    box.textContent = event.message + schedulerPosition(event);
    box.classList.toggle("notice-warn", event.running);
    $("#btn-scheduler-start").disabled = event.running;
    $("#btn-scheduler-stop").disabled = !event.running;
    if (flipped || !event.running) {
      renderScheduler();
      renderAccounts();
    } else {
      renderLaunchBar();
    }
    logStatus("Scheduler: " + event.message);
  }

  function schedulerPosition(event) {
    return event.index === undefined || event.index === null ? "" : ` (${event.index + 1}/${event.total})`;
  }

  function startScheduler() {
    const chosen = chosenCharacters();
    if (!chosen.length) {
      logStatus("Scheduler: tick at least one character first", "error");
      return;
    }
    send({
      type: "scheduler_start",
      accounts: chosen.map((c) => ({ account_id: c.account_id, display_name: c.display_name })),
      delay_seconds: Math.max(0, Number(schedDelay.value) || 0),
      repeat: $("#sched-repeat").checked,
    });
  }

  function syncGap(from, to) {
    to.value = from.value;
    renderLaunchBar();
  }

  // ---- Frame ----

  function showView(name) {
    state.view = name;
    content.dataset.view = name;
    Object.entries(views).forEach(([key, section]) => {
      section.hidden = key !== name;
    });
    navItems.forEach((item) => item.classList.toggle("is-active", item.dataset.view === name));

    if (name === "scheduler") renderScheduler();
    if (name === "clients") requestClients(true);
    if (name === "store") requestStore();
    if (name === "settings") restoreConfigToUI();
    if (name === "logs") statusLog.scrollTop = statusLog.scrollHeight;
    startClientsPolling();
  }

  function renderStats() {
    const characters = state.sessions.reduce((n, s) => n + s.accounts.length, 0);
    const parts = [plural(state.sessions.length, "account"), plural(characters, "character")];
    if (state.clients) parts.push(state.clients.length + " running");
    topbarStats.textContent = parts.join(" · ");
  }

  function renderVersion() {
    const status = state.launcherStatus;
    if (!status || !status.launcher_version) return;
    let text = "Launcher v" + status.launcher_version;
    if (status.launcher_update) text = "v" + status.launcher_version + " · update available";
    else if (status.release_tag) text = "v" + status.launcher_version + " · up to date";
    sidebarVersion.textContent = text;
    sidebarVersion.title = text;
    clientStatus.classList.toggle("warn", !!status.launcher_update);
    clientStatus.classList.toggle("ok", !status.launcher_update && !!status.release_tag);
  }

  function logStatus(msg, cls) {
    const entry = el("div", "log-entry" + (cls ? " " + cls : ""));
    entry.appendChild(el("span", "log-time", formatClock(new Date(), true)));
    entry.appendChild(el("span", "log-text", msg));

    const pinned = statusLog.scrollHeight - statusLog.scrollTop - statusLog.clientHeight < 24;
    statusLog.appendChild(entry);
    while (statusLog.childElementCount > MAX_LOG_ENTRIES) {
      statusLog.removeChild(statusLog.firstElementChild);
    }
    if (pinned) statusLog.scrollTop = statusLog.scrollHeight;

    [statusbar, launchStatus].forEach((line) => {
      line.textContent = msg;
      line.classList.toggle("success", cls === "success");
      line.classList.toggle("error", cls === "error");
    });
  }

  // ---- Play ----

  function renderHero() {
    const store = state.store;
    const name = (store && store.signed_in && store.name) || (state.sessions[0] && state.sessions[0].display_name);
    heroGreeting.textContent = name ? greeting() + ", " + name : "Welcome back";

    state.heroTarget = heroTarget();
    const target = state.heroTarget;
    btnHeroPlay.hidden = !target;
    if (target) {
      const launchingThis = state.launchingAccountId === target.account.accountId;
      heroPlayLabel.textContent = launchingThis ? "Launching…" : "Play " + (target.account.displayName || "character");
      btnHeroPlay.disabled = !!state.launchingAccountId;
    }
    heroSubtitle.textContent = state.sessions.length
      ? "Press Play on a character, or tick several and launch them one after another."
      : "Log in with your Jagex account and its characters appear here, ready to launch.";
  }

  function heroTarget() {
    for (const session of state.sessions) {
      const last = session.accounts.find((a) => a.accountId === session.last_account_id);
      if (last) return { session, account: last };
    }
    for (const session of state.sessions) {
      const named = session.accounts.find((a) => a.displayName);
      if (named) return { session, account: named };
    }
    return null;
  }

  function renderAccounts() {
    renderStats();
    renderHero();
    accountsList.textContent = "";
    accountsEmpty.hidden = state.sessions.length > 0;

    const allOpen = state.sessions.length > 0 && state.sessions.every((s) => !state.collapsed.has(s.user_id));
    btnExpandAll.textContent = allOpen ? "Collapse all" : "Expand all";
    btnExpandAll.hidden = state.sessions.length === 0;
    btnLogin.textContent = state.sessions.length ? "+ Add account" : "Login with Jagex";
    btnLogin.classList.toggle("btn-primary", !state.sessions.length);
    btnLogin.classList.toggle("btn-glass", !!state.sessions.length);

    state.sessions.forEach((session) => accountsList.appendChild(renderGroup(session)));
    renderSelectionChrome();
  }

  function renderGroup(session) {
    const open = !state.collapsed.has(session.user_id);
    const wrap = el("section", "group" + (open ? " is-open" : ""));

    const head = el("div", "group-head");
    head.setAttribute("role", "button");
    head.setAttribute("tabindex", "0");
    head.setAttribute("aria-expanded", String(open));
    head.appendChild(icon("i-chevron", "chev"));
    head.appendChild(el("span", "group-name", session.display_name || "Jagex account"));
    head.appendChild(el("span", "group-meta", plural(session.accounts.length, "character")));
    head.appendChild(
      button("Log out", "btn-danger group-logout", !!state.launchingAccountId, () =>
        send({ type: "logout", user_id: session.user_id })
      )
    );

    const toggle = () => {
      if (state.collapsed.has(session.user_id)) state.collapsed.delete(session.user_id);
      else state.collapsed.add(session.user_id);
      saveSet(COLLAPSED_KEY, state.collapsed);
      renderAccounts();
    };
    head.addEventListener("click", toggle);
    head.addEventListener("keydown", (e) => {
      if (e.target !== head || (e.key !== "Enter" && e.key !== " ")) return;
      e.preventDefault();
      toggle();
    });
    wrap.appendChild(head);

    if (!open) return wrap;
    if (!session.accounts.length) {
      wrap.appendChild(el("div", "empty", "No characters on this account yet."));
      return wrap;
    }
    const grid = el("div", "account-grid");
    session.accounts.forEach((account) => grid.appendChild(renderCard(session, account)));
    wrap.appendChild(grid);
    return wrap;
  }

  function renderCard(session, account) {
    const chosen = schedulerChosen.has(account.accountId);
    const card = el("div", "account-card" + (chosen ? " selected" : ""));

    const tick = el("button", "tick");
    tick.type = "button";
    tick.setAttribute("role", "checkbox");
    tick.setAttribute("aria-checked", String(chosen));
    tick.setAttribute("aria-label", "Include " + (account.displayName || "character") + " in the launch");
    tick.disabled = !account.displayName || state.schedulerRunning;
    tick.appendChild(el("span", "", "✓"));
    tick.addEventListener("click", () => {
      const on = !schedulerChosen.has(account.accountId);
      if (on) schedulerChosen.add(account.accountId);
      else schedulerChosen.delete(account.accountId);
      tick.setAttribute("aria-checked", String(on));
      card.classList.toggle("selected", on);
      renderSelectionChrome();
    });
    card.appendChild(tick);

    const known = state.characters[account.accountId];
    const portrait = el("span", "avatar char-face");
    portrait.dataset.accountId = account.accountId;
    paintFace(portrait, account.displayName, known);
    card.appendChild(portrait);
    if (account.displayName && known === undefined) {
      state.characters[account.accountId] = null;
      send({ type: "character_info", account_id: account.accountId, display_name: account.displayName });
    }

    const main = el("div", "card-main");
    main.appendChild(el("span", "card-name", account.displayName || "Unnamed character"));
    main.appendChild(el("span", "card-meta", characterMeta(known)));
    card.appendChild(main);

    const side = el("div", "card-side");
    if (session.last_account_id === account.accountId) side.appendChild(el("span", "status-pill last", "Last played"));
    const launchingThis = state.launchingAccountId === account.accountId;
    side.appendChild(
      button(launchingThis ? "Launching…" : "Play", "card-play", !!state.launchingAccountId, () => launch(session, account))
    );
    card.appendChild(side);
    return card;
  }

  function renderSelectionChrome() {
    const characters = everyCharacter();
    const allChosen = characters.length > 0 && characters.every((c) => schedulerChosen.has(c.account_id));
    btnSelectAll.textContent = allChosen ? "Clear selection" : "Select all";
    btnSelectAll.hidden = characters.length === 0;
    btnSelectAll.disabled = state.schedulerRunning;
    renderLaunchBar();
  }

  function renderLaunchBar() {
    const running = state.schedulerRunning;
    launchGap.disabled = running;
    btnLaunch.classList.toggle("btn-primary", !running);
    btnLaunch.classList.toggle("btn-danger", running);
    if (running) {
      const status = state.schedulerStatus;
      launchLabel.textContent = "Launching in turn";
      launchSub.textContent = status ? status.message + schedulerPosition(status) : "";
      btnLaunch.textContent = "Stop";
      btnLaunch.disabled = false;
      return;
    }
    const n = chosenCharacters().length;
    const gap = Math.max(0, Number(launchGap.value) || 0);
    launchLabel.textContent = n ? plural(n, "character") + " selected" : "No characters selected";
    launchSub.textContent = n
      ? "They start one after another, " + gap + " s apart"
      : "Tick the characters you want to start one after another";
    btnLaunch.textContent = "Launch";
    btnLaunch.disabled = n === 0;
  }

  function launch(session, account) {
    if (state.launchingAccountId) return;
    state.launchingAccountId = account.accountId;
    session.last_account_id = account.accountId;
    renderAccounts();
    logStatus("Launching " + (account.displayName || "character") + "…");
    send({
      type: "launch",
      account_id: account.accountId,
      display_name: account.displayName || "",
    });
  }

  // ---- Clients ----

  function requestClients(force) {
    const now = Date.now();
    const inFlight = state.clientsRequestAt && now - state.clientsRequestAt < CLIENTS_REQUEST_TIMEOUT_MS;
    if (inFlight && !force) return;
    state.clientsRequestAt = now;
    send({ type: "list_clients" });
  }

  function desiredClientsPollMs() {
    if (state.view !== "clients" && !railQuery.matches) return CLIENTS_BACKGROUND_POLL_MS;
    return (state.clients || []).some((c) => c.state === "starting") ? CLIENTS_STARTING_POLL_MS : CLIENTS_POLL_MS;
  }

  function startClientsPolling() {
    const interval = desiredClientsPollMs();
    if (state.clientsPollTimer !== null && state.clientsPollMs === interval) return;
    if (state.clientsPollTimer !== null) clearInterval(state.clientsPollTimer);
    state.clientsPollMs = interval;
    state.clientsPollTimer = setInterval(() => requestClients(false), interval);
  }

  function clientStateMeta(s) {
    switch (s) {
      case "active":
        return { label: "Active", cls: "active" };
      case "unloaded":
        return { label: "Disabled", cls: "" };
      case "not-injected":
        return { label: "Not injected", cls: "" };
      case "starting":
        return { label: "Injecting", cls: "starting" };
      default:
        return { label: "Error", cls: "error" };
    }
  }

  function isBusy(map, key) {
    const startedAt = map[key];
    if (!startedAt) return false;
    if (Date.now() - startedAt > BUSY_TIMEOUT_MS) {
      delete map[key];
      return false;
    }
    return true;
  }

  function pruneBusyPids() {
    const live = new Set((state.clients || []).map((c) => String(c.pid)));
    Object.keys(state.busyPids).forEach((pid) => {
      if (!live.has(pid)) delete state.busyPids[pid];
    });
  }

  function renderClients() {
    const clients = state.clients || [];
    navClientsCount.textContent = String(clients.length);
    navClientsCount.hidden = clients.length === 0;

    // Rebuilding identical cards every poll would steal focus from a button, so redraw only on change.
    const signature = JSON.stringify(
      clients.map((c) => [c.pid, c.state, c.version, c.reloads, c.detail, c.plan, Object.keys(characterPlans()).length, isBusy(state.busyPids, c.pid)])
    );
    if (signature === state.renderedClients) return;
    state.renderedClients = signature;

    clientsList.textContent = "";
    runningList.textContent = "";
    clientsEmpty.hidden = clients.length > 0;
    runningEmpty.hidden = clients.length > 0;
    clients.forEach((c) => {
      clientsList.appendChild(clientCard(c));
      runningList.appendChild(clientCard(c));
    });
  }

  function clientCard(c) {
    const meta = clientStateMeta(c.state);
    const busy = isBusy(state.busyPids, c.pid);
    const card = el("div", "run-card");

    const top = el("div", "run-top");
    top.appendChild(el("span", "run-name", "Client " + c.pid));
    top.appendChild(badge(meta.label, meta.cls));
    card.appendChild(top);

    const parts = [];
    if (c.version) parts.push("engine v" + c.version);
    if (c.state === "active" && typeof c.reloads === "number") parts.push(plural(c.reloads, "reload"));
    card.appendChild(el("span", "run-script", parts.length ? parts.join(" · ") : "No engine loaded"));
    // A client that never answered its control socket has nothing else to act on, so show the bootstrap's words.
    if (c.detail) card.appendChild(el("span", "run-note run-error", c.detail));
    if (c.plan) {
      card.appendChild(el("span", "run-note", "Plan " + c.plan.name + ": " + c.plan.detail));
      if (c.plan.problem) card.appendChild(el("span", "run-note run-error", c.plan.problem));
    }

    const actions = el("div", "run-buttons");
    if (c.state === "not-injected" || c.state === "unloaded") {
      actions.appendChild(button(busy ? "…" : "Inject", "btn-strong", busy, () => clientAction(c.pid, "inject_client")));
    } else if (c.state === "active") {
      actions.appendChild(button(busy ? "…" : "Reinject", "", busy, () => clientAction(c.pid, "reinject_client")));
      actions.appendChild(button("Uninject", "btn-danger", busy, () => clientAction(c.pid, "uninject_client")));
    }
    if (c.state === "active" && c.plan) {
      actions.appendChild(button("Stop plan", "", busy, () => clientAction(c.pid, "stop_plan")));
    } else if (c.state === "active" && Object.keys(characterPlans()).length) {
      const select = el("select", "input input-small plan-picker");
      select.title = "Whose script plan to run on this client";
      Object.keys(characterPlans()).forEach((id) => select.appendChild(new Option(nameOf(id) + "'s plan", id)));
      actions.appendChild(select);
      actions.appendChild(
        button("Run plan", "btn-strong", busy, () => {
          const plan = characterPlans()[select.value];
          if (!plan) return;
          state.busyPids[c.pid] = Date.now();
          state.renderedClients = null;
          renderClients();
          send({ type: "run_plan", pid: c.pid, plan: plan });
        }),
      );
    }
    // "starting" and "error" have no socket to command yet, and re-injecting a mapped bootstrap is never right.
    if (actions.childElementCount) card.appendChild(actions);
    return card;
  }

  function clientAction(pid, type) {
    state.busyPids[pid] = Date.now();
    state.renderedClients = null;
    renderClients();
    send({ type: type, pid: pid });
  }

  // The launcher cannot replace itself while running, so this is a prompt and a link, not an install.
  function renderLauncherUpdate() {
    const status = state.launcherStatus;
    const update = status && status.launcher_update;
    launcherUpdate.hidden = !update;
    if (!update) return;
    const size = formatSize(update.size);
    launcherUpdateDetail.textContent =
      "You have " + status.launcher_version + " · " + (status.release_tag || "latest") + (size ? " · " + size : "");
  }

  // ---- Store ----

  function requestStore() {
    send({ type: "store_status" });
    send({ type: "store_scripts" });
  }

  function renderUserCard() {
    const status = state.store;
    const signedIn = !!(status && status.signed_in);
    const pairing = status && status.pairing;
    userName.textContent = signedIn ? status.name || "Signed in" : "Not signed in";
    userSub.textContent = signedIn
      ? "Store linked. Scripts you buy install by themselves."
      : pairing
        ? "Approve code " + pairing.code + " in your browser."
        : "Sign in so the scripts you buy install here.";
    btnUserSignIn.hidden = signedIn;
    btnUserSignIn.disabled = !!pairing;
    $("#user-sign-in-label").textContent = pairing ? "Waiting…" : "Sign in";
    btnUserSignOut.hidden = !signedIn;
  }

  function renderStore() {
    const status = state.store;
    const signedIn = !!(status && status.signed_in);

    storeAccount.textContent = signedIn ? status.name || "Signed in" : "Not signed in";
    storeOrigin.textContent = status ? status.store.replace(/^https?:\/\//, "") : "…";
    btnStoreSignIn.hidden = signedIn;
    btnStoreSignOut.hidden = !signedIn;

    const pairing = status && status.pairing;
    storePairing.hidden = !pairing;
    if (pairing) {
      storeCode.textContent = pairing.code;
      storePairingLink.dataset.url = pairing.url;
    }
    btnStoreSignIn.disabled = !!pairing;
    $("#store-sign-in-label").textContent = pairing ? "Waiting for approval…" : "Sign in with Discord";

    storeError.hidden = !state.storeError;
    if (state.storeError) storeError.textContent = state.storeError;

    storeList.textContent = "";
    const scripts = state.storeScripts || [];
    scripts.forEach((s) => storeList.appendChild(renderStoreRow(s)));
    // An empty list only means "nothing to buy" once the store has answered.
    storeEmpty.hidden = !(state.storeScripts && scripts.length === 0 && !state.storeError);
  }

  function metaItem(label, value) {
    const span = el("span", "store-meta-item", label + " ");
    span.appendChild(el("b", "", value));
    return span;
  }

  function renderStoreRow(s) {
    const busy = isBusy(state.busyStore, s.internal_name);
    const installed = s.install_state === "current";
    const outdated = s.install_state === "outdated";
    // Paid scripts the engine downloads into the game at every start; nothing is saved on the PC.
    const inGame = s.install_state === "in_game";
    const card = el("div", "store-card");

    card.appendChild(el("span", "store-name", s.name));
    const badges = el("div", "store-badges");
    if (s.free) badges.appendChild(badge("Free", "ok"));
    if (s.pending) badges.appendChild(badge("Trial ready", "ok"));
    else if (s.owned) badges.appendChild(badge(s.trial ? "Trial" : s.free ? "Claimed" : "Owned", "ok"));
    else badges.appendChild(badge(s.free ? "Not claimed" : "Not owned", "warn"));
    if (s.sale_percent && !s.free) badges.appendChild(badge(s.sale_percent + "% off", "ok"));
    if (s.installable && installed) badges.appendChild(badge("Installed", "ok"));
    if (s.installable && outdated) badges.appendChild(badge("Update available", "warn"));
    if (s.installable && inGame && s.owned) badges.appendChild(badge("Loads in game", "ok"));
    card.appendChild(badges);

    const metaLine = el("div", "store-meta");
    metaLine.appendChild(metaItem("price", priceLine(s)));
    if (s.installable) metaLine.appendChild(metaItem("version", "v" + s.version));
    if (s.owned && s.expires_at && !s.pending) {
      metaLine.appendChild(metaItem(s.trial ? "trial ends" : "until", formatClock(new Date(s.expires_at))));
    }
    card.appendChild(metaLine);

    // Installing before the game is the point: the trial hour has not started yet.
    if (s.pending) {
      card.appendChild(
        el(
          "p",
          "store-desc",
          inGame
            ? "Your trial hour starts when you launch the game with Project X loaded, not now."
            : "Your trial hour starts when you launch the game with Project X loaded, not now. Install it first."
        )
      );
    }
    if (s.owned && s.installable && inGame) {
      card.appendChild(
        el("p", "store-desc", "Downloads into the game each time you start it with Project X. Nothing is saved on your PC.")
      );
    }

    const actions = el("div", "store-actions");
    if (s.owned && s.installable && !inGame) {
      const label = busy ? "…" : outdated ? "Update" : installed ? "Reinstall" : "Install";
      actions.appendChild(
        button(label, "btn-primary", busy, () => {
          state.busyStore[s.internal_name] = Date.now();
          send({ type: "store_install", internal_name: s.internal_name });
          renderStore();
        })
      );
    }
    if (s.store_url) {
      const label = s.owned ? (s.free ? "Renew free" : "View") : s.free ? "Get it free" : "Buy";
      actions.appendChild(
        button(label, s.owned ? "" : "btn-strong", false, () => send({ type: "open_url", url: s.store_url }))
      );
    }
    if (actions.childElementCount) card.appendChild(actions);
    return card;
  }

  // Quoted the way the store page quotes it: free, or the price of each tier in X Tokens.
  function priceLine(s) {
    if (s.free) return "Free · 30 days, renews free";
    const p = s.prices;
    if (!p) return "See the store";
    return p.week + " X a week · " + p.fortnight + " X a fortnight · " + p.month + " X a month";
  }

  // Asked on every launch while signed out: linking the account is what makes bought scripts arrive.
  function renderSignInDialog() {
    const status = state.store;
    if (!status) return;

    if (status.signed_in) {
      if (signInDialog.open) signInDialog.close();
      return;
    }
    if (state.signInDismissed) return;

    const pairing = status.pairing;
    signInWaiting.hidden = !pairing;
    if (pairing) {
      $("#sign-in-code").textContent = pairing.code;
      $("#sign-in-reopen").dataset.url = pairing.url;
    }
    signInGo.disabled = !!pairing;
    $("#sign-in-go-label").textContent = pairing ? "Waiting for approval…" : "Sign in with Discord";

    signInError.hidden = !status.error;
    if (status.error) signInError.textContent = status.error;

    if (!signInDialog.open) signInDialog.showModal();
  }

  function dismissSignIn() {
    state.signInDismissed = true;
    if (signInDialog.open) signInDialog.close();
  }

  function storeSignOut() {
    // Signing out on purpose is an answer; the prompt waits for the next launch.
    state.signInDismissed = true;
    send({ type: "store_sign_out" });
  }

  // ---- Settings ----

  function restoreConfigToUI() {
    if (!state.config) return;
    chkCloseAfter.checked = !!state.config.close_after_launch;
    chkAutoInject.checked = !!state.config.auto_inject_projectx;
    chkDebugLogging.checked = !!state.config.debug_logging;
    inpLaunchCmd.value = state.config.custom_launch_command || "";
  }

  function saveSettings() {
    if (!state.config) return;
    state.config.close_after_launch = chkCloseAfter.checked;
    state.config.auto_inject_projectx = chkAutoInject.checked;
    state.config.debug_logging = chkDebugLogging.checked;
    state.config.custom_launch_command = inpLaunchCmd.value || null;
    send({ type: "save_config", config: state.config });
  }

  // ---- Wiring ----

  function openPairingLink(e) {
    e.preventDefault();
    const url = e.currentTarget.dataset.url;
    if (url) send({ type: "open_url", url: url });
  }

  navItems.forEach((item) => item.addEventListener("click", () => showView(item.dataset.view)));
  $("#btn-scheduler-start").addEventListener("click", startScheduler);
  $("#btn-scheduler-stop").addEventListener("click", () => send({ type: "scheduler_stop" }));
  $("#plan-copy").addEventListener("change", copyPlan);
  $("#btn-plan-add-step").addEventListener("click", () => $("#plan-steps").appendChild(stepRow({})));
  $("#btn-plan-cancel").addEventListener("click", closePlanEditor);
  $("#btn-plan-delete").addEventListener("click", deletePlan);
  $("#plan-editor").addEventListener("submit", savePlan);

  btnLogin.addEventListener("click", () => send({ type: "login" }));
  btnHeroPlay.addEventListener("click", () => {
    const target = state.heroTarget;
    if (target) launch(target.session, target.account);
  });
  btnExpandAll.addEventListener("click", () => {
    const allOpen = state.sessions.every((s) => !state.collapsed.has(s.user_id));
    state.sessions.forEach((s) => {
      if (allOpen) state.collapsed.add(s.user_id);
      else state.collapsed.delete(s.user_id);
    });
    saveSet(COLLAPSED_KEY, state.collapsed);
    renderAccounts();
  });
  btnSelectAll.addEventListener("click", () => {
    const characters = everyCharacter();
    const allChosen = characters.every((c) => schedulerChosen.has(c.account_id));
    characters.forEach((c) => {
      if (allChosen) schedulerChosen.delete(c.account_id);
      else schedulerChosen.add(c.account_id);
    });
    renderAccounts();
  });
  btnLaunch.addEventListener("click", () => {
    if (state.schedulerRunning) send({ type: "scheduler_stop" });
    else startScheduler();
  });
  launchGap.addEventListener("input", () => syncGap(launchGap, schedDelay));
  schedDelay.addEventListener("input", () => syncGap(schedDelay, launchGap));

  btnRefreshClients.addEventListener("click", () => requestClients(true));
  btnSideRefresh.addEventListener("click", () => requestClients(true));
  railQuery.addEventListener("change", startClientsPolling);

  btnStoreSignIn.addEventListener("click", () => send({ type: "store_sign_in" }));
  btnUserSignIn.addEventListener("click", () => send({ type: "store_sign_in" }));
  signInGo.addEventListener("click", () => send({ type: "store_sign_in" }));
  $("#sign-in-later").addEventListener("click", dismissSignIn);
  signInDialog.addEventListener("cancel", (e) => {
    e.preventDefault();
    dismissSignIn();
  });
  $("#sign-in-reopen").addEventListener("click", openPairingLink);
  storePairingLink.addEventListener("click", openPairingLink);
  btnStoreSignOut.addEventListener("click", storeSignOut);
  btnUserSignOut.addEventListener("click", storeSignOut);
  btnStoreRefresh.addEventListener("click", requestStore);
  btnStorePage.addEventListener("click", () => {
    const base = state.store && state.store.store;
    if (base) send({ type: "open_url", url: base + "/rs3/store" });
  });

  btnLauncherUpdate.addEventListener("click", () => {
    const update = state.launcherStatus && state.launcherStatus.launcher_update;
    if (update) send({ type: "open_url", url: update.url });
  });

  btnClearLogs.addEventListener("click", () => {
    statusLog.textContent = "";
    statusbar.textContent = "";
    launchStatus.textContent = "";
  });

  btnSaveSettings.addEventListener("click", saveSettings);
  btnResetSettings.addEventListener("click", restoreConfigToUI);

  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && state.view !== "play") showView("play");
  });

  // The backend answers "ready" with "init", so the payload always lands after the callback is installed.
  renderAccounts();
  send({ type: "ready" });
})();

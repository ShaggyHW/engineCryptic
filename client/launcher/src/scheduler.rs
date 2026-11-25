//! Script plans: which scripts a client runs, for how long each, and in what order. Each character has its own, kept
//! in the launcher's config; the engine in each client runs its plan itself (`PLAN` on the control socket), so a plan
//! carries on if the launcher is closed.

use std::thread;
use std::time::{Duration, Instant};

use base64::Engine as _;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

/// One script in a plan, run for a random while between `min_minutes` and `max_minutes`, with `settings` (the script's
/// setting keys to values) on top of the ones the player saved for it.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlanStep {
    pub script: String,
    pub min_minutes: u32,
    pub max_minutes: u32,
    #[serde(default, skip_serializing_if = "Map::is_empty")]
    pub settings: Map<String, Value>,
}

fn default_pause_min() -> u32 {
    15
}

fn default_pause_max() -> u32 {
    90
}

/// Scripts run in turn, with a short random pause between them; a plan that repeats starts over after its last.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScriptPlan {
    pub name: String,
    pub steps: Vec<PlanStep>,
    #[serde(default)]
    pub repeat: bool,
    #[serde(default = "default_pause_min")]
    pub pause_min_seconds: u32,
    #[serde(default = "default_pause_max")]
    pub pause_max_seconds: u32,
}

/// Where a client's plan has got to, from `PLAN STATUS`.
#[derive(Debug, Clone, Serialize)]
pub struct PlanState {
    pub name: String,
    pub detail: String,
    pub problem: Option<String>,
}

/// How long a freshly injected engine may take to load before the plan is given up on.
const ENGINE_WAIT: Duration = Duration::from_secs(300);
const POLL: Duration = Duration::from_secs(3);

/// Hands `plan` to the engine in `pid`, waiting for it to finish loading first. Blocks; run it off the UI thread.
pub fn deliver(pid: u32, plan: &ScriptPlan) -> anyhow::Result<String> {
    let json = serde_json::to_string(plan)?;
    let command = format!("PLAN SET {}", base64::engine::general_purpose::STANDARD.encode(json));
    let deadline = Instant::now() + ENGINE_WAIT;
    loop {
        let active = crate::game::control::send_command(pid, "STATUS")
            .map(|reply| reply.starts_with("OK active"))
            .unwrap_or(false);
        if active {
            let reply = crate::game::control::send_command(pid, &command)?;
            if let Some(ok) = reply.strip_prefix("OK ") {
                return Ok(ok.to_string());
            }
            if !reply.contains("engine not loaded") {
                anyhow::bail!("{}", reply.trim_start_matches("ERR ").trim());
            }
        }
        if Instant::now() >= deadline {
            anyhow::bail!("the engine did not load within {} minutes", ENGINE_WAIT.as_secs() / 60);
        }
        thread::sleep(POLL);
    }
}

/// [`deliver`] on its own thread, for a client that is still coming up.
pub fn deliver_in_background(pid: u32, plan: ScriptPlan) {
    let spawned = thread::Builder::new().name("projectx-plan".to_string()).spawn(move || match deliver(pid, &plan) {
        Ok(reply) => log::info!("Script plan '{}' handed to client {}: {}", plan.name, pid, reply),
        Err(e) => log::warn!("Script plan '{}' could not be handed to client {}: {}", plan.name, pid, e),
    });
    if let Err(e) = spawned {
        log::warn!("Could not start the script plan thread: {}", e);
    }
}

/// A script's settings as the engine in `pid` describes them (`CONFIG`): its name there and a list of settings, each
/// with a key, name, kind, value and, for a choice, its choices.
pub fn script_settings(pid: u32, script: &str) -> anyhow::Result<(String, Value)> {
    let reply = crate::game::control::send_command(pid, &format!("CONFIG {}", script))?;
    let body = match reply.trim().strip_prefix("OK ") {
        Some(body) => body,
        None => anyhow::bail!("{}", reply.trim_start_matches("ERR ").trim()),
    };
    let (name, encoded) = body.split_once('|').ok_or_else(|| anyhow::anyhow!("the engine sent no settings"))?;
    let json = base64::engine::general_purpose::STANDARD.decode(encoded)?;
    Ok((name.to_string(), serde_json::from_slice(&json)?))
}

/// Ends the plan in `pid` and the script it is running.
pub fn stop(pid: u32) -> anyhow::Result<String> {
    let reply = crate::game::control::send_command(pid, "PLAN STOP")?;
    match reply.strip_prefix("OK ") {
        Some(ok) => Ok(ok.to_string()),
        None => anyhow::bail!("{}", reply),
    }
}

/// The plan `pid` is running, or None when it runs none or cannot say.
pub fn status(pid: u32) -> Option<PlanState> {
    let reply = crate::game::control::send_command(pid, "PLAN STATUS").ok()?;
    parse_status(&reply)
}

fn parse_status(reply: &str) -> Option<PlanState> {
    let body = reply.trim().strip_prefix("OK ")?;
    if body == "none" {
        return None;
    }
    let mut parts = body.splitn(3, '|');
    let name = parts.next()?.to_string();
    let detail = parts.next().unwrap_or("").to_string();
    let problem = parts.next().map(str::to_string).filter(|p| !p.is_empty());
    Some(PlanState { name, detail, problem })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn status_lines_from_the_engine_are_read() {
        assert!(parse_status("OK none").is_none());
        assert!(parse_status("ERR engine not loaded").is_none());
        let state = parse_status("OK Skills|Fishing (1/2), 12 min left|").unwrap();
        assert_eq!(state.name, "Skills");
        assert_eq!(state.detail, "Fishing (1/2), 12 min left");
        assert!(state.problem.is_none());
        let trouble = parse_status("OK Skills|Next: Mining in 40s|Agil would not start").unwrap();
        assert_eq!(trouble.problem.as_deref(), Some("Agil would not start"));
    }

    #[test]
    fn a_plan_written_by_the_launcher_has_the_shape_the_engine_reads() {
        let plan = ScriptPlan {
            name: "Skills".into(),
            steps: vec![PlanStep {
                script: "Fishing".into(),
                min_minutes: 30,
                max_minutes: 60,
                settings: Map::from_iter([("bank".to_string(), Value::Bool(true))]),
            }],
            repeat: true,
            pause_min_seconds: 15,
            pause_max_seconds: 90,
        };
        let json: serde_json::Value = serde_json::from_str(&serde_json::to_string(&plan).unwrap()).unwrap();
        assert_eq!(json["steps"][0]["min_minutes"], 30);
        assert_eq!(json["pause_max_seconds"], 90);
        assert_eq!(json["repeat"], true);
        assert_eq!(json["steps"][0]["settings"]["bank"], true);
        let old: ScriptPlan = serde_json::from_str(r#"{"name":"x","steps":[]}"#).unwrap();
        assert_eq!(old.pause_min_seconds, 15);
    }
}

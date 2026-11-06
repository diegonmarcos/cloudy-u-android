//! The jump-host helpers of the webserver's shell: `fleet-nix`, `fleet-termux`, `fleet-adb`, `fleet-ipc`.
//!
//! They are POSIX sh templates under assets/jump/ (code only: no key, no host key, no token). [`render`] fills the `{{...}}`
//! placeholders from build.json::ssh_doors, which build.rs resolved from each owner's own declaration, so no port, package, user
//! or action name is written twice; [`install`] writes the rendered files, mode 755, into a directory the shell's PATH names.
//!
//! No UID magic. The helpers never switch uid and never enter another app's sandbox: they dial each environment's OWN door
//! (nested ssh to its sshd, an intent to its service, adb to the shell uid), so the right uid and userland stay in force.

use crate::door::{self, Door};
use std::path::Path;

pub const TEMPLATES: [(&str, &str); 4] = [
    ("fleet-nix", include_str!("../assets/jump/fleet-nix")),
    ("fleet-termux", include_str!("../assets/jump/fleet-termux")),
    ("fleet-adb", include_str!("../assets/jump/fleet-adb")),
    ("fleet-ipc", include_str!("../assets/jump/fleet-ipc")),
];

fn door_by<'a>(doors: &'a [Door], id: &str) -> Result<&'a Door, String> {
    doors.iter().find(|d| d.id == id).ok_or_else(|| format!("build.json::ssh_doors has no door {}", id))
}

/// Fill one template from the declared doors. Err when a placeholder is left over or a door lacks what the helper needs.
pub fn render(tpl: &str, doors: &[Door]) -> Result<String, String> {
    let nix = door_by(doors, "nix")?;
    let termux = door_by(doors, "termux")?;
    let ctl = nix.control.as_ref().ok_or("the nix door has no control hook")?;
    let port = |d: &Door| d.port.map(|p| p.to_string()).ok_or(format!("door {} has no port", d.id));
    let out = tpl
        .replace("{{NIX_PORT}}", &port(nix)?)
        .replace("{{NIX_USER}}", &nix.user)
        .replace("{{NIX_PKG}}", &nix.package)
        .replace("{{NIX_SERVICE}}", &ctl.service)
        .replace("{{NIX_START}}", &ctl.action_start)
        .replace("{{TERMUX_PORT}}", &port(termux)?)
        .replace("{{TERMUX_USER}}", &termux.user)
        .replace("{{TERMUX_PKG}}", &termux.package);
    if let Some(i) = out.find("{{") {
        return Err(format!("unfilled placeholder near {:?}", &out[i..(i + 20).min(out.len())]));
    }
    Ok(out)
}

/// Write the four helpers into `dir` (created if needed), executable. Returns the names written.
pub fn install(dir: &Path) -> Result<Vec<String>, String> {
    std::fs::create_dir_all(dir).map_err(|e| format!("cannot create {}: {}", dir.display(), e))?;
    let doors = &door::cfg().doors;
    let mut done = vec![];
    for (name, tpl) in TEMPLATES {
        let body = render(tpl, doors)?;
        let path = dir.join(name);
        std::fs::write(&path, body).map_err(|e| format!("cannot write {}: {}", path.display(), e))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).map_err(|e| e.to_string())?;
        }
        done.push(name.to_string());
    }
    Ok(done)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_helper_renders_from_the_declaration() {
        let doors = &door::cfg().doors;
        for (name, tpl) in TEMPLATES {
            let out = render(tpl, doors).unwrap_or_else(|e| panic!("{}: {}", name, e));
            assert!(out.starts_with("#!/system/bin/sh"), "{}", name);
        }
    }

    #[test]
    fn the_jump_helpers_dial_the_declared_ports_and_never_switch_uid() {
        let doors = &door::cfg().doors;
        let nix = render(TEMPLATES[0].1, doors).unwrap();
        assert!(nix.contains("PORT=8024") && nix.contains("USER=nix-on-droid") && nix.contains("exec ssh -p"));
        assert!(nix.contains("cld.termux.nix/com.termux.app.MeshSshdService"), "the start offer uses the exported hook");
        let termux = render(TEMPLATES[1].1, doors).unwrap();
        assert!(termux.contains("PORT=8023") && termux.contains("exec ssh -p") && termux.contains("RunCommandService"));
        for (name, tpl) in TEMPLATES {
            let out = render(tpl, doors).unwrap();
            for banned in ["su ", "su -", "setuid", "run-as", "BEGIN OPENSSH PRIVATE KEY", "ssh-ed25519 "] {
                assert!(!out.contains(banned), "{} contains {:?}", name, banned);
            }
        }
    }

    #[test]
    fn an_unfilled_placeholder_is_an_error() {
        assert!(render("x {{NOPE}}", &door::cfg().doors).is_err());
    }
}

//! The c3-report/v1 envelope (schemas/c3-report-v1.schema.json is the contract) and the reading statuses.

use crate::json::{self, obj, s, Json};

pub const SCHEMA: &str = "c3-report/v1";

/// OK, WARN and DOWN carry numbers; the other four always carry their reason and never a number.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Status {
    Ok,
    Warn,
    Down,
    Pending,
    /// the vantage could not look ("unreachable from here: <reason>")
    Blind,
    /// no source exists ("not served: <reason>")
    NotServed,
    /// dependency-gated ("not probed: blocked by <root>")
    Gated,
}

impl Status {
    pub fn id(self) -> &'static str {
        match self {
            Status::Ok => "ok",
            Status::Warn => "warn",
            Status::Down => "down",
            Status::Pending => "pending",
            Status::Blind => "blind",
            Status::NotServed => "not_served",
            Status::Gated => "gated",
        }
    }
    pub fn parse(v: &str) -> Status {
        match v {
            "ok" => Status::Ok,
            "warn" => Status::Warn,
            "down" => Status::Down,
            "blind" | "unknown" => Status::Blind,
            "not_served" => Status::NotServed,
            "gated" => Status::Gated,
            _ => Status::Pending,
        }
    }
    /// Only these three may print a number.
    pub fn carries_number(self) -> bool {
        matches!(self, Status::Ok | Status::Warn | Status::Down)
    }
    /// A failing reading for attribution: down, blind or gated.
    pub fn failing(self) -> bool {
        matches!(self, Status::Down | Status::Blind | Status::Gated)
    }
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Phases {
    pub dns: Option<u64>,
    pub tcp: Option<u64>,
    pub tls: Option<u64>,
    pub http: Option<u64>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Check {
    pub id: String,
    pub layer: String,
    pub entity_kind: String,
    pub entity_id: String,
    pub name: String,
    pub status: Status,
    pub details: String,
    pub duration_ms: Option<u64>,
    pub phases: Phases,
    pub code: Option<u16>,
    pub needs: Vec<String>,
    pub evidence: Vec<String>,
    pub explained_by: Option<String>,
    /// measured | inferred
    pub confidence: String,
    pub vantage: String,
    pub at: String,
}

impl Check {
    pub fn new(id: &str, layer: &str, status: Status) -> Check {
        Check {
            id: id.into(), layer: layer.into(), entity_kind: String::new(), entity_id: String::new(), name: id.into(), status,
            details: String::new(), duration_ms: None, phases: Phases::default(), code: None, needs: vec![], evidence: vec![],
            explained_by: None, confidence: "measured".into(), vantage: String::new(), at: String::new(),
        }
    }
    pub fn needs(mut self, n: &[&str]) -> Check {
        self.needs = n.iter().map(|x| x.to_string()).collect();
        self
    }
    pub fn evidence(mut self, n: &[&str]) -> Check {
        self.evidence = n.iter().map(|x| x.to_string()).collect();
        self
    }
    pub fn details(mut self, d: &str) -> Check {
        self.details = d.into();
        self
    }

    pub fn to_json(&self) -> Json {
        let opt = |v: Option<u64>| v.map(|n| Json::Num(n as f64)).unwrap_or(Json::Null);
        obj(vec![
            ("id", s(&self.id)),
            ("layer", s(&self.layer)),
            ("entity", obj(vec![("kind", s(&self.entity_kind)), ("id", s(&self.entity_id))])),
            ("name", s(&self.name)),
            ("passed", Json::Bool(self.status == Status::Ok)),
            ("status", s(self.status.id())),
            ("details", s(&self.details)),
            ("duration_ms", opt(self.duration_ms)),
            ("phases", obj(vec![("dns", opt(self.phases.dns)), ("tcp", opt(self.phases.tcp)), ("tls", opt(self.phases.tls)), ("http", opt(self.phases.http))])),
            ("code", self.code.map(|c| Json::Num(c as f64)).unwrap_or(Json::Null)),
            ("needs", Json::Arr(self.needs.iter().map(|x| s(x)).collect())),
            ("evidence", Json::Arr(self.evidence.iter().map(|x| s(x)).collect())),
            ("explained_by", self.explained_by.as_ref().map(|x| s(x)).unwrap_or(Json::Null)),
            ("confidence", s(&self.confidence)),
            ("vantage", s(&self.vantage)),
            ("at", s(&self.at)),
        ])
    }

    pub fn from_json(v: &Json) -> Check {
        let n = |o: &Json, k: &str| o.num(k).map(|x| x as u64);
        let ph = v.get("phases").cloned().unwrap_or(Json::Null);
        let ent = v.get("entity").cloned().unwrap_or(Json::Null);
        Check {
            id: v.str("id"), layer: v.str("layer"), entity_kind: ent.str("kind"), entity_id: ent.str("id"), name: v.str("name"),
            status: Status::parse(&v.str("status")), details: v.str("details"), duration_ms: n(v, "duration_ms"),
            phases: Phases { dns: n(&ph, "dns"), tcp: n(&ph, "tcp"), tls: n(&ph, "tls"), http: n(&ph, "http") },
            code: v.num("code").map(|c| c as u16), needs: v.strs("needs"), evidence: v.strs("evidence"),
            explained_by: match v.get("explained_by") { Some(Json::Str(x)) => Some(x.clone()), _ => None },
            confidence: { let c = v.str("confidence"); if c.is_empty() { "measured".into() } else { c } },
            vantage: v.str("vantage"), at: v.str("at"),
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Summary {
    pub total_checks: u64,
    pub passed: u64,
    pub failed: u64,
    pub warnings: u64,
    pub critical: u64,
    pub unknown: u64,
    pub not_served: u64,
    pub gated: u64,
}

pub fn summarize(checks: &[Check]) -> Summary {
    let c = |f: &dyn Fn(Status) -> bool| checks.iter().filter(|x| f(x.status)).count() as u64;
    Summary {
        total_checks: checks.len() as u64,
        passed: c(&|s| s == Status::Ok),
        failed: c(&|s| s == Status::Down),
        warnings: c(&|s| s == Status::Warn),
        critical: checks.iter().filter(|x| x.status == Status::Down && x.explained_by.is_none()).count() as u64,
        unknown: c(&|s| s == Status::Blind || s == Status::Pending),
        not_served: c(&|s| s == Status::NotServed),
        gated: c(&|s| s == Status::Gated),
    }
}

/// The envelope one engine run writes.
#[derive(Debug, Clone, PartialEq)]
pub struct Report {
    pub engine: String,
    pub engine_version: String,
    pub run_id: String,
    pub runner: String,
    pub vantage: String,
    pub started: String,
    pub finished: String,
    pub checks: Vec<Check>,
    pub roots: Vec<String>,
    pub missing: Vec<(String, String)>,
    pub redactions: u64,
}

impl Report {
    /// The overall status: RED when a root is down, YELLOW for any warn, blind or gated, GREEN when all ok, UNKNOWN when nothing measured.
    pub fn status(&self) -> &'static str {
        if self.checks.iter().any(|c| c.status == Status::Down) { return "RED"; }
        if self.checks.iter().any(|c| matches!(c.status, Status::Warn | Status::Blind | Status::Gated)) { return "YELLOW"; }
        if !self.checks.is_empty() && self.checks.iter().all(|c| c.status == Status::Ok || c.status == Status::NotServed)
            && self.checks.iter().any(|c| c.status == Status::Ok) { return "GREEN"; }
        "UNKNOWN"
    }

    pub fn to_json(&self) -> Json {
        let sm = summarize(&self.checks);
        let chains: Vec<(String, Json)> = self.roots.iter().map(|r| {
            let ids: Vec<Json> = self.checks.iter().filter(|c| c.explained_by.as_deref() == Some(r.as_str())).map(|c| s(&c.id)).collect();
            (r.clone(), Json::Arr(ids))
        }).collect();
        let num = |n: u64| Json::Num(n as f64);
        obj(vec![
            ("schema", s(SCHEMA)),
            ("engine", s(&self.engine)),
            ("engine_version", s(&self.engine_version)),
            ("run", obj(vec![("id", s(&self.run_id)), ("started", s(&self.started)), ("finished", s(&self.finished)), ("runner", s(&self.runner)), ("vantage", s(&self.vantage))])),
            ("status", s(self.status())),
            ("summary", obj(vec![
                ("total_checks", num(sm.total_checks)), ("passed", num(sm.passed)), ("failed", num(sm.failed)), ("warnings", num(sm.warnings)),
                ("critical", num(sm.critical)), ("unknown", num(sm.unknown)), ("not_served", num(sm.not_served)), ("gated", num(sm.gated)),
            ])),
            ("sections", Json::Arr(vec![])),
            ("checks", Json::Arr(self.checks.iter().map(|c| c.to_json()).collect())),
            ("findings", Json::Arr(vec![])),
            ("attribution", obj(vec![("roots", Json::Arr(self.roots.iter().map(|r| s(r)).collect())), ("chains", Json::Obj(chains))])),
            ("missing", Json::Arr(self.missing.iter().map(|(i, t)| obj(vec![("id", s(i)), ("text", s(t))])).collect())),
            ("redactions", num(self.redactions)),
        ])
    }

    pub fn from_json(v: &Json) -> Result<Report, String> {
        if v.str("schema") != SCHEMA { return Err(format!("schema is '{}', want {}", v.str("schema"), SCHEMA)); }
        let run = v.get("run").cloned().unwrap_or(Json::Null);
        let attr = v.get("attribution").cloned().unwrap_or(Json::Null);
        Ok(Report {
            engine: v.str("engine"), engine_version: v.str("engine_version"), run_id: run.str("id"), runner: run.str("runner"),
            vantage: run.str("vantage"), started: run.str("started"), finished: run.str("finished"),
            checks: v.arr("checks").iter().map(Check::from_json).collect(),
            roots: attr.strs("roots"),
            missing: v.arr("missing").iter().map(|m| (m.str("id"), m.str("text"))).collect(),
            redactions: v.num("redactions").unwrap_or(0.0) as u64,
        })
    }

    pub fn write(&self) -> String {
        json::write(&self.to_json())
    }
}

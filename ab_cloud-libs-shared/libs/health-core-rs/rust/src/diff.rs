//! Diff of two snapshots keyed by (section, row key): added, removed, changed. Volatile rows (uptime, counters, timestamps) are ignored.

#[derive(Debug, Clone, PartialEq)]
pub struct Row {
    pub section: String,
    pub key: String,
    pub value: String,
    pub volatile: bool,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Diff {
    pub added: Vec<(String, String)>,
    pub removed: Vec<(String, String)>,
    pub changed: Vec<(String, String, String, String)>,
}

pub fn diff(prev: &[Row], cur: &[Row]) -> Diff {
    let mut d = Diff::default();
    let stable = |rs: &[Row]| rs.iter().filter(|r| !r.volatile).cloned().collect::<Vec<Row>>();
    let (p, c) = (stable(prev), stable(cur));
    for r in &c {
        match p.iter().find(|x| x.section == r.section && x.key == r.key) {
            None => d.added.push((r.section.clone(), r.key.clone())),
            Some(o) if o.value != r.value => d.changed.push((r.section.clone(), r.key.clone(), o.value.clone(), r.value.clone())),
            _ => {}
        }
    }
    for r in &p {
        if !c.iter().any(|x| x.section == r.section && x.key == r.key) { d.removed.push((r.section.clone(), r.key.clone())); }
    }
    d
}

#[cfg(test)]
mod tests {
    use super::*;
    fn r(s: &str, k: &str, v: &str, vol: bool) -> Row { Row { section: s.into(), key: k.into(), value: v.into(), volatile: vol } }

    #[test]
    fn added_removed_changed_and_volatile_ignored() {
        let prev = vec![r("cpu_mem", "swap used", "120Mi", false), r("cpu_mem", "uptime", "3d", true), r("disks", "/", "40%", false)];
        let cur = vec![r("cpu_mem", "swap used", "512Mi", false), r("cpu_mem", "uptime", "4d", true), r("containers", "x restarts", "4", false)];
        let d = diff(&prev, &cur);
        assert_eq!(d.changed, vec![("cpu_mem".into(), "swap used".into(), "120Mi".into(), "512Mi".into())]);
        assert_eq!(d.added, vec![("containers".into(), "x restarts".into())]);
        assert_eq!(d.removed, vec![("disks".into(), "/".into())]);
        assert_eq!(diff(&cur, &cur), Diff::default());
    }
}

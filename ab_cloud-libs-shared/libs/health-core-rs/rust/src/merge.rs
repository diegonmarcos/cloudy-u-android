//! Merge of the vantages (phone, fleet, gha): one row per check id, each reading labelled with its vantage. The merged status is the
//! worst MEASURED reading; an inferred reading counts only when nothing was measured.

use crate::model::{Check, Status};

fn severity(s: Status) -> u8 {
    match s {
        Status::Down => 6,
        Status::Warn => 5,
        Status::Gated => 4,
        Status::Blind => 3,
        Status::Pending => 2,
        Status::NotServed => 1,
        Status::Ok => 0,
    }
}

/// (merged check, every reading as (vantage, status)) per id, in first-seen order.
pub fn merge(reports: &[(&str, Vec<Check>)]) -> Vec<(Check, Vec<(String, Status)>)> {
    let mut out: Vec<(Check, Vec<(String, Status)>)> = Vec::new();
    for (vantage, checks) in reports {
        for c in checks {
            let mut c = c.clone();
            c.vantage = vantage.to_string();
            match out.iter().position(|(m, _)| m.id == c.id) {
                None => { let v = vec![(vantage.to_string(), c.status)]; out.push((c, v)); }
                Some(i) => {
                    let (m, v) = &mut out[i];
                    v.push((vantage.to_string(), c.status));
                    let measured_new = c.confidence != "inferred";
                    let measured_old = m.confidence != "inferred";
                    let replace = (measured_new && !measured_old) || (measured_new == measured_old && severity(c.status) > severity(m.status));
                    if replace { *m = c; }
                }
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn worst_measured_wins_and_every_vantage_is_listed() {
        let phone = vec![Check::new("a", "lights", Status::Ok), Check::new("b", "vms", Status::Down)];
        let mut inferred = Check::new("b", "vms", Status::Down);
        inferred.confidence = "inferred".into();
        let mut fleet_b = Check::new("b", "vms", Status::Ok);
        fleet_b.confidence = "measured".into();
        let fleet = vec![Check::new("a", "lights", Status::Warn), fleet_b];
        let m = merge(&[("phone", vec![phone[0].clone(), inferred]), ("fleet", fleet)]);
        assert_eq!(m[0].0.status, Status::Warn);
        assert_eq!(m[0].0.vantage, "fleet");
        assert_eq!(m[0].1, vec![("phone".to_string(), Status::Ok), ("fleet".to_string(), Status::Warn)]);
        assert_eq!(m[1].0.status, Status::Ok, "a measured OK beats an inferred DOWN");
        let m2 = merge(&[("phone", phone.clone()), ("fleet", vec![Check::new("b", "vms", Status::Ok)])]);
        assert_eq!(m2[1].0.status, Status::Down, "two measured: the worst");
    }
}

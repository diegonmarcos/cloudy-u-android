//! The load budget of a fleet read (rev 2 section 6): one VM at a time with the hub last, per-section output cap, skip under memory
//! pressure (reported, never silent) and a minimum interval per subject.

#[derive(Debug, Clone, PartialEq)]
pub struct Budget {
    pub per_command_s: u64,
    pub per_vm_s: u64,
    pub section_cap_kb: usize,
    pub min_free_mb: u64,
    pub min_interval_min: u64,
}

impl Default for Budget {
    fn default() -> Budget {
        Budget { per_command_s: 5, per_vm_s: 30, section_cap_kb: 256, min_free_mb: 80, min_interval_min: 10 }
    }
}

/// None = go; Some(reason) = skipped, with the reason the report prints.
pub fn skip_for_memory(b: &Budget, free_mb: Option<u64>) -> Option<String> {
    match free_mb {
        Some(f) if f < b.min_free_mb => Some(format!("skipped: VM under memory pressure ({} MB free, budget {} MB)", f, b.min_free_mb)),
        Some(_) => None,
        None => Some("skipped: free memory could not be read".to_string()),
    }
}

/// The output of one section, cut at the cap (on a char boundary) with a marker saying how much was cut.
pub fn cap(b: &Budget, out: &str) -> String {
    let max = b.section_cap_kb * 1024;
    if out.len() <= max { return out.to_string(); }
    let mut end = max;
    while !out.is_char_boundary(end) { end -= 1; }   // index 0 is always a boundary
    format!("{}\n[cut: {} of {} bytes kept]", &out[..end], end, out.len())
}

/// The visiting order: the declared order with every hub moved to the end (hubs keep their relative order).
pub fn order_hub_last(vms: &[String], hubs: &[String]) -> Vec<String> {
    let mut a: Vec<String> = vms.iter().filter(|v| !hubs.contains(v)).cloned().collect();
    a.extend(vms.iter().filter(|v| hubs.contains(v)).cloned());
    a
}

/// Rate limit: a new run of a subject is allowed only `min_interval_min` after the last one; else the minutes to wait.
pub fn wait_minutes(b: &Budget, last_run_epoch_s: Option<u64>, now_epoch_s: u64) -> Option<u64> {
    let last = last_run_epoch_s?;
    let next = last + b.min_interval_min * 60;
    if now_epoch_s >= next { None } else { Some((next - now_epoch_s + 59) / 60) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn memory_pressure_skips_with_a_reason_at_the_boundary() {
        let b = Budget::default();
        assert_eq!(skip_for_memory(&b, Some(80)), None, "at the budget: go");
        assert!(skip_for_memory(&b, Some(79)).unwrap().contains("79 MB free, budget 80 MB"));
        assert!(skip_for_memory(&b, None).unwrap().contains("could not be read"));
        assert_eq!(skip_for_memory(&b, Some(81)), None);
    }

    #[test]
    fn cap_keeps_small_output_and_cuts_large_output_on_a_char_boundary() {
        let b = Budget { section_cap_kb: 1, ..Budget::default() };
        let small = "x".repeat(1024);
        assert_eq!(cap(&b, &small), small, "exactly the cap is kept whole");
        let big = format!("{}é{}", "x".repeat(1023), "y".repeat(10));
        let c = cap(&b, &big);
        assert!(c.starts_with(&"x".repeat(1023)));
        assert!(c.ends_with(&format!("[cut: 1023 of {} bytes kept]", big.len())), "{}", c);
        let over = "z".repeat(1025);
        assert!(cap(&b, &over).contains("[cut: 1024 of 1025 bytes kept]"));
    }

    #[test]
    fn hubs_go_last_in_their_order() {
        let v: Vec<String> = ["hub1", "a", "hub2", "b"].iter().map(|x| x.to_string()).collect();
        let h: Vec<String> = ["hub2", "hub1"].iter().map(|x| x.to_string()).collect();
        assert_eq!(order_hub_last(&v, &h), vec!["a", "b", "hub1", "hub2"]);
        assert_eq!(order_hub_last(&v, &[]), v);
    }

    #[test]
    fn rate_limit_waits_until_the_interval_has_passed() {
        let b = Budget::default();
        assert_eq!(wait_minutes(&b, None, 1000), None);
        assert_eq!(wait_minutes(&b, Some(1000), 1000 + 600), None, "exactly the interval: allowed");
        assert_eq!(wait_minutes(&b, Some(1000), 1000 + 599), Some(1));
        assert_eq!(wait_minutes(&b, Some(1000), 1000), Some(10));
        assert_eq!(wait_minutes(&b, Some(1000), 1000 + 61), Some(9));
    }
}

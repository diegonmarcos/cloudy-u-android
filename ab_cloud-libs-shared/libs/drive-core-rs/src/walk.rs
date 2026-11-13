//! Parallel directory walk with aggregation in Rust: one call returns the totals, the largest folders and files, the bytes per
//! extension and (when asked) the file listing, so a walk of 200 000 entries crosses the JNI boundary once, not 200 000 times.
//!
//! Never follows a symbolic link (a link back up the tree would loop, one out of it would count another volume's bytes). A folder
//! the walk cannot read is skipped, not an error. Bounded by `max_entries` and `budget_ms`; a stopped walk says so in `partial`.

use std::collections::{BTreeMap, HashMap};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use crate::wire;

#[derive(Debug, Clone)]
pub struct Opts {
    /// Directory names never entered (anywhere in the tree).
    pub skip_names: Vec<String>,
    /// Paths relative to the root never entered (`Android/data`).
    pub skip_rel: Vec<String>,
    pub max_entries: u64,
    pub budget_ms: u64,
    /// How many largest files / folders to return (0 = none for files, all for folders).
    pub top_files: usize,
    pub top_folders: usize,
    /// Also return every file as (path, size, mtime ms).
    pub list_files: bool,
    pub threads: usize,
    /// Key of the files directly in the root in the folder totals.
    pub loose_label: String,
}

impl Default for Opts {
    fn default() -> Self {
        Opts { skip_names: vec![], skip_rel: vec![], max_entries: 200_000, budget_ms: 25_000, top_files: 25, top_folders: 25, list_files: false, threads: 4, loose_label: String::new() }
    }
}

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Agg {
    pub files: u64,
    pub dirs: u64,
    pub bytes: u64,
    /// Every entry looked at (files, folders, links): what `max_entries` bounds.
    pub entries: u64,
    pub partial: String,
    /// (folder key, bytes) largest first, key breaks a tie. A folder key is a first-level folder's absolute path, or the loose label.
    pub folders: Vec<(String, u64)>,
    /// (bytes, path) largest first, path breaks a tie.
    pub top_files: Vec<(u64, String)>,
    /// extension exactly as written in the name ("" when none) -> (count, bytes)
    pub by_ext: BTreeMap<String, (u64, u64)>,
    /// (path, bytes, mtime ms) sorted by path; only when `list_files`.
    pub listing: Vec<(String, u64, i64)>,
}

/// The extension the way Kotlin's FileClassifier.extensionOf reads it, case preserved: the text after the last dot, "" for a dot-file or a trailing dot.
pub fn ext_of(name: &str) -> &str {
    match name.rfind('.') {
        Some(i) if i > 0 && i + 1 < name.len() => &name[i + 1..],
        _ => "",
    }
}

#[derive(Default)]
struct Local {
    files: u64,
    dirs: u64,
    bytes: u64,
    folders: HashMap<Arc<str>, u64>,
    top: Vec<(u64, String)>,
    by_ext: BTreeMap<String, (u64, u64)>,
    listing: Vec<(String, u64, i64)>,
}

struct Job {
    dir: PathBuf,
    rel: String,
    top: Arc<str>,
}

struct Queue {
    stack: Vec<Job>,
    busy: usize,
}

fn keep_top(v: &mut Vec<(u64, String)>, n: usize) {
    v.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.cmp(&b.1)));
    v.truncate(n);
}

/// The mtime in ms at WHOLE-SECOND resolution: the platforms' own readers disagree below it (Android's java.nio reads seconds, Rust
/// reads nanoseconds), and a sync compare must not see a change in a file nobody wrote. The Kotlin reference truncates the same way.
fn mtime_ms(md: &std::fs::Metadata) -> i64 {
    md.modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| (d.as_secs() * 1000) as i64)
        .unwrap_or(0)
}

/// Walk `root`. The root itself is never counted; its direct files are keyed by `opts.loose_label`, each direct sub-folder by its own path.
pub fn walk(root: &Path, opts: &Opts) -> Result<Agg, String> {
    let rd = std::fs::read_dir(root).map_err(|e| format!("{}: {}", root.display(), e))?;
    let t0 = Instant::now();
    let budget = Duration::from_millis(opts.budget_ms);
    let entries = AtomicU64::new(0);
    let stop = AtomicBool::new(false);
    let reason: Mutex<String> = Mutex::new(String::new());
    let halt = |why: String| {
        if !stop.swap(true, Ordering::SeqCst) {
            if let Ok(mut r) = reason.lock() {
                *r = why;
            }
        }
    };

    let mut root_local = Local::default();
    let mut seed: Vec<Job> = Vec::new();
    let loose: Arc<str> = Arc::from(opts.loose_label.as_str());
    let top_n = if opts.top_files == 0 { usize::MAX } else { opts.top_files };

    for ent in rd.flatten() {
        if stop.load(Ordering::Relaxed) {
            break;
        }
        if entries.fetch_add(1, Ordering::Relaxed) + 1 > opts.max_entries {
            halt(format!("partial, stopped after {} entries", opts.max_entries));
            break;
        }
        let ft = match ent.file_type() {
            Ok(t) => t,
            Err(_) => continue,
        };
        if ft.is_symlink() {
            continue;
        }
        let name = ent.file_name().to_string_lossy().to_string();
        if ft.is_dir() {
            if opts.skip_names.iter().any(|s| s == &name) || opts.skip_rel.iter().any(|s| s == &name) {
                continue;
            }
            let p = ent.path();
            root_local.dirs += 1;
            seed.push(Job { top: Arc::from(p.to_string_lossy().as_ref()), dir: p, rel: name });
        } else if ft.is_file() {
            if let Ok(md) = ent.metadata() {
                add_file(&mut root_local, &loose, &name, &ent.path(), md.len(), mtime_ms(&md), opts);
            }
        }
    }

    let q = Mutex::new(Queue { stack: seed, busy: 0 });
    let cv = Condvar::new();
    let locals: Mutex<Vec<Local>> = Mutex::new(Vec::new());
    let workers = opts.threads.clamp(1, 8);

    let work = || {
        let mut loc = Local::default();
        let mut seen: u64 = 0;
        loop {
            let job = {
                let mut g = q.lock().unwrap();
                loop {
                    if stop.load(Ordering::Relaxed) {
                        g.stack.clear();
                    }
                    if let Some(j) = g.stack.pop() {
                        g.busy += 1;
                        break Some(j);
                    }
                    if g.busy == 0 {
                        cv.notify_all();
                        break None;
                    }
                    g = cv.wait(g).unwrap();
                }
            };
            let job = match job {
                Some(j) => j,
                None => break,
            };
            let mut children: Vec<Job> = Vec::new();
            if let Ok(rd) = std::fs::read_dir(&job.dir) {
                for ent in rd.flatten() {
                    if stop.load(Ordering::Relaxed) {
                        break;
                    }
                    if entries.fetch_add(1, Ordering::Relaxed) + 1 > opts.max_entries {
                        halt(format!("partial, stopped after {} entries", opts.max_entries));
                        break;
                    }
                    seen += 1;
                    if seen & 1023 == 0 && t0.elapsed() > budget {
                        halt(format!("partial, stopped after {} s", opts.budget_ms / 1000));
                        break;
                    }
                    let ft = match ent.file_type() {
                        Ok(t) => t,
                        Err(_) => continue,
                    };
                    if ft.is_symlink() {
                        continue;
                    }
                    let name = ent.file_name().to_string_lossy().to_string();
                    if ft.is_dir() {
                        let rel = format!("{}/{}", job.rel, name);
                        if opts.skip_names.iter().any(|s| s == &name) || opts.skip_rel.iter().any(|s| s == &rel) {
                            continue;
                        }
                        loc.dirs += 1;
                        children.push(Job { dir: ent.path(), rel, top: job.top.clone() });
                    } else if ft.is_file() {
                        if let Ok(md) = ent.metadata() {
                            add_file(&mut loc, &job.top, &name, &ent.path(), md.len(), mtime_ms(&md), opts);
                        }
                    }
                }
            }
            {
                let mut g = q.lock().unwrap();
                g.busy -= 1;
                let added = !children.is_empty();
                g.stack.extend(children);
                if added || g.busy == 0 {
                    cv.notify_all();
                }
            }
            if loc.top.len() > top_n.saturating_mul(2).max(64) && top_n != usize::MAX {
                keep_top(&mut loc.top, top_n);
            }
        }
        locals.lock().unwrap().push(loc);
    };

    if workers <= 1 || q.lock().unwrap().stack.is_empty() {
        work();
    } else {
        std::thread::scope(|s| {
            for _ in 0..workers {
                s.spawn(&work);
            }
        });
    }

    let mut all = locals.into_inner().unwrap_or_default();
    all.push(root_local);
    let mut agg = Agg { entries: entries.load(Ordering::Relaxed).min(opts.max_entries.saturating_add(1)), ..Default::default() };
    let mut folders: HashMap<Arc<str>, u64> = HashMap::new();
    let mut top: Vec<(u64, String)> = Vec::new();
    for l in all {
        agg.files += l.files;
        agg.dirs += l.dirs;
        agg.bytes += l.bytes;
        for (k, v) in l.folders {
            *folders.entry(k).or_insert(0) += v;
        }
        top.extend(l.top);
        for (k, (c, b)) in l.by_ext {
            let e = agg.by_ext.entry(k).or_insert((0, 0));
            e.0 += c;
            e.1 += b;
        }
        agg.listing.extend(l.listing);
    }
    let mut fv: Vec<(String, u64)> = folders.into_iter().map(|(k, v)| (k.to_string(), v)).collect();
    fv.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
    if opts.top_folders > 0 {
        fv.truncate(opts.top_folders);
    }
    agg.folders = fv;
    keep_top(&mut top, if opts.top_files == 0 { 0 } else { opts.top_files });
    agg.top_files = top;
    agg.listing.sort_by(|a, b| a.0.cmp(&b.0));
    agg.partial = reason.into_inner().unwrap_or_default();
    Ok(agg)
}

fn add_file(l: &mut Local, top: &Arc<str>, name: &str, path: &Path, len: u64, mtime: i64, opts: &Opts) {
    l.files += 1;
    l.bytes += len;
    *l.folders.entry(top.clone()).or_insert(0) += len;
    let e = l.by_ext.entry(ext_of(name).to_string()).or_insert((0, 0));
    e.0 += 1;
    e.1 += len;
    let p = path.to_string_lossy().to_string();
    if opts.top_files > 0 {
        l.top.push((len, p.clone()));
    }
    if opts.list_files {
        l.listing.push((p, len, mtime));
    }
}

/// The wire answer (see the module doc of [crate]).
pub fn encode(a: &Agg) -> String {
    let mut s = format!("S\t{}\t{}\t{}\t{}\n", a.files, a.dirs, a.bytes, a.entries);
    s.push_str(&format!("P\t{}\n", wire::esc(&a.partial)));
    for (k, v) in &a.folders {
        s.push_str(&format!("F\t{}\t{}\n", wire::esc(k), v));
    }
    for (b, p) in &a.top_files {
        s.push_str(&format!("T\t{}\t{}\n", b, wire::esc(p)));
    }
    for (k, (c, b)) in &a.by_ext {
        s.push_str(&format!("X\t{}\t{}\t{}\n", wire::esc(k), c, b));
    }
    for (p, b, m) in &a.listing {
        s.push_str(&format!("L\t{}\t{}\t{}\n", wire::esc(p), b, m));
    }
    s
}

/// Options from the `key=value` lines Kotlin sends (values escaped; `skip` and `skip_rel` repeat).
pub fn parse_opts(text: &str) -> Opts {
    let mut o = Opts::default();
    for line in text.split('\n').filter(|l| !l.is_empty()) {
        let (k, v) = match line.split_once('=') {
            Some(kv) => kv,
            None => continue,
        };
        let v = wire::unesc(v);
        match k {
            "skip" => o.skip_names.push(v),
            "skip_rel" => o.skip_rel.push(v),
            "max_entries" => o.max_entries = v.parse().unwrap_or(o.max_entries),
            "budget_ms" => o.budget_ms = v.parse().unwrap_or(o.budget_ms),
            "top_files" => o.top_files = v.parse().unwrap_or(o.top_files),
            "top_folders" => o.top_folders = v.parse().unwrap_or(o.top_folders),
            "list" => o.list_files = v == "1",
            "threads" => o.threads = v.parse().unwrap_or(o.threads),
            "loose" => o.loose_label = v,
            _ => {}
        }
    }
    o
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::{tmpdir, write};

    fn fixture(tag: &str) -> PathBuf {
        let d = tmpdir(tag);
        write(&d.join("loose.txt"), &[0; 7]);
        write(&d.join("a/x.JPG"), &[0; 100]);
        write(&d.join("a/deep/y.jpg"), &[0; 300]);
        write(&d.join("a/deep/deeper/z"), &[0; 5]);
        write(&d.join("b/big.mp4"), &[0; 1000]);
        write(&d.join("b/.hidden"), &[0; 2]);
        write(&d.join("b/trail."), &[0; 3]);
        write(&d.join("skipme/s.bin"), &[0; 9999]);
        write(&d.join("Android/data/app/f"), &[0; 8888]);
        write(&d.join("Android/media/m.png"), &[0; 50]);
        std::fs::create_dir_all(d.join("empty")).unwrap();
        d
    }

    fn opts() -> Opts {
        Opts { skip_names: vec!["skipme".into()], skip_rel: vec!["Android/data".into()], loose_label: "LOOSE".into(), ..Default::default() }
    }

    #[test]
    fn extension_reading_matches_the_kotlin_classifier() {
        assert_eq!(ext_of("a.JPG"), "JPG");
        assert_eq!(ext_of("a.tar.gz"), "gz");
        assert_eq!(ext_of(".hidden"), "");
        assert_eq!(ext_of("trail."), "");
        assert_eq!(ext_of("noext"), "");
    }

    #[test]
    fn aggregates_and_ranks() {
        let d = fixture("walk-agg");
        let a = walk(&d, &opts()).unwrap();
        assert_eq!(a.partial, "");
        assert_eq!(a.files, 8);
        assert_eq!(a.bytes, 7 + 100 + 300 + 5 + 1000 + 2 + 3 + 50);
        let key = |n: &str| d.join(n).to_string_lossy().to_string();
        assert_eq!(a.folders[0], (key("b"), 1005));
        assert_eq!(a.folders[1], (key("a"), 405));
        assert_eq!(a.folders[2], (key("Android"), 50));
        assert_eq!(a.folders[3], ("LOOSE".to_string(), 7));
        assert!(!a.folders.iter().any(|f| f.0.contains("skipme")));
        assert_eq!(a.top_files[0], (1000, d.join("b/big.mp4").to_string_lossy().to_string()));
        assert_eq!(a.by_ext["JPG"], (1, 100));
        assert_eq!(a.by_ext["jpg"], (1, 300));
        assert_eq!(a.by_ext["txt"], (1, 7));
        assert_eq!(a.by_ext[""], (3, 5 + 2 + 3));
        assert!(a.listing.is_empty());
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn threads_do_not_change_the_answer() {
        let d = fixture("walk-par");
        let mut o = opts();
        o.threads = 1;
        let one = walk(&d, &o).unwrap();
        o.threads = 8;
        o.list_files = true;
        let many = walk(&d, &o).unwrap();
        let mut one_l = one.clone();
        one_l.listing = many.listing.clone();
        assert_eq!(one_l, many);
        assert_eq!(many.listing.len(), 8);
        assert!(many.listing.windows(2).all(|w| w[0].0 <= w[1].0));
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn never_follows_links_and_stops_at_the_cap() {
        let d = fixture("walk-link");
        let out = tmpdir("walk-link-out");
        write(&out.join("secret.mp4"), &[0; 5000]);
        #[cfg(unix)]
        {
            std::os::unix::fs::symlink(&out, d.join("linkdir")).unwrap();
            std::os::unix::fs::symlink(out.join("secret.mp4"), d.join("linkfile.mp4")).unwrap();
        }
        let a = walk(&d, &opts()).unwrap();
        assert_eq!(a.bytes, 7 + 100 + 300 + 5 + 1000 + 2 + 3 + 50);
        let mut o = opts();
        o.max_entries = 3;
        o.threads = 1;
        let p = walk(&d, &o).unwrap();
        assert_eq!(p.partial, "partial, stopped after 3 entries");
        assert!(p.files < a.files);
        let _ = std::fs::remove_dir_all(&d);
        let _ = std::fs::remove_dir_all(&out);
    }

    #[test]
    fn an_unreadable_root_is_an_error_and_options_parse() {
        assert!(walk(Path::new("/nonexistent-drive-core-rs"), &Opts::default()).is_err());
        let o = parse_opts("skip=.git\nskip_rel=Android/data\nmax_entries=5\nbudget_ms=9\ntop_files=3\ntop_folders=2\nlist=1\nthreads=2\nloose=L\\tX\nbogus\n");
        assert_eq!(o.skip_names, vec![".git"]);
        assert_eq!(o.skip_rel, vec!["Android/data"]);
        assert_eq!((o.max_entries, o.budget_ms, o.top_files, o.top_folders, o.threads), (5, 9, 3, 2, 2));
        assert!(o.list_files);
        assert_eq!(o.loose_label, "L\tX");
    }
}

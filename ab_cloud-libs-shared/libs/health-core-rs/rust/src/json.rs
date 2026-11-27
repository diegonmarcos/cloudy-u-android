//! A small JSON value, parser and writer (std only). Objects keep their key order, so output is deterministic.

#[derive(Debug, Clone, PartialEq)]
pub enum Json {
    Null,
    Bool(bool),
    Num(f64),
    Str(String),
    Arr(Vec<Json>),
    Obj(Vec<(String, Json)>),
}

impl Json {
    pub fn get(&self, k: &str) -> Option<&Json> {
        match self {
            Json::Obj(kv) => kv.iter().find(|(a, _)| a == k).map(|(_, v)| v),
            _ => None,
        }
    }
    pub fn str(&self, k: &str) -> String {
        match self.get(k) {
            Some(Json::Str(s)) => s.clone(),
            Some(Json::Num(n)) => fmt_num(*n),
            Some(Json::Bool(b)) => b.to_string(),
            _ => String::new(),
        }
    }
    pub fn num(&self, k: &str) -> Option<f64> {
        match self.get(k) {
            Some(Json::Num(n)) => Some(*n),
            _ => None,
        }
    }
    pub fn arr(&self, k: &str) -> Vec<Json> {
        match self.get(k) {
            Some(Json::Arr(a)) => a.clone(),
            _ => Vec::new(),
        }
    }
    pub fn strs(&self, k: &str) -> Vec<String> {
        self.arr(k).into_iter().filter_map(|v| if let Json::Str(s) = v { Some(s) } else { None }).collect()
    }
    pub fn as_str(&self) -> Option<&str> {
        if let Json::Str(s) = self { Some(s) } else { None }
    }
}

pub fn fmt_num(n: f64) -> String {
    if n.fract() == 0.0 && n.abs() < 1e15 { format!("{}", n as i64) } else { format!("{}", n) }
}

pub fn obj(kv: Vec<(&str, Json)>) -> Json {
    Json::Obj(kv.into_iter().map(|(k, v)| (k.to_string(), v)).collect())
}
pub fn s(v: &str) -> Json {
    Json::Str(v.to_string())
}

fn esc(out: &mut String, v: &str) {
    out.push('"');
    for c in v.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
}

pub fn write(v: &Json) -> String {
    let mut out = String::new();
    w(v, &mut out);
    out
}

fn w(v: &Json, out: &mut String) {
    match v {
        Json::Null => out.push_str("null"),
        Json::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
        Json::Num(n) => out.push_str(&fmt_num(*n)),
        Json::Str(x) => esc(out, x),
        Json::Arr(a) => {
            out.push('[');
            for (i, x) in a.iter().enumerate() {
                if i > 0 { out.push(','); }
                w(x, out);
            }
            out.push(']');
        }
        Json::Obj(kv) => {
            out.push('{');
            for (i, (k, x)) in kv.iter().enumerate() {
                if i > 0 { out.push(','); }
                esc(out, k);
                out.push(':');
                w(x, out);
            }
            out.push('}');
        }
    }
}

pub fn parse(text: &str) -> Result<Json, String> {
    let b: Vec<char> = text.chars().collect();
    let mut i = 0usize;
    let v = value(&b, &mut i)?;
    ws(&b, &mut i);
    if i != b.len() { return Err(format!("trailing data at {}", i)); }
    Ok(v)
}

fn ws(b: &[char], i: &mut usize) {
    while *i < b.len() && b[*i].is_whitespace() { *i += 1; }
}

fn value(b: &[char], i: &mut usize) -> Result<Json, String> {
    ws(b, i);
    if *i >= b.len() { return Err("unexpected end".into()); }
    match b[*i] {
        '{' => {
            *i += 1;
            let mut kv = Vec::new();
            ws(b, i);
            if *i < b.len() && b[*i] == '}' { *i += 1; return Ok(Json::Obj(kv)); }
            loop {
                ws(b, i);
                let k = match value(b, i)? { Json::Str(k) => k, _ => return Err(format!("object key expected at {}", i)) };
                ws(b, i);
                if *i >= b.len() || b[*i] != ':' { return Err(format!("':' expected at {}", i)); }
                *i += 1;
                let v = value(b, i)?;
                kv.push((k, v));
                ws(b, i);
                if *i < b.len() && b[*i] == ',' { *i += 1; continue; }
                if *i < b.len() && b[*i] == '}' { *i += 1; return Ok(Json::Obj(kv)); }
                return Err(format!("',' or '}}' expected at {}", i));
            }
        }
        '[' => {
            *i += 1;
            let mut a = Vec::new();
            ws(b, i);
            if *i < b.len() && b[*i] == ']' { *i += 1; return Ok(Json::Arr(a)); }
            loop {
                a.push(value(b, i)?);
                ws(b, i);
                if *i < b.len() && b[*i] == ',' { *i += 1; continue; }
                if *i < b.len() && b[*i] == ']' { *i += 1; return Ok(Json::Arr(a)); }
                return Err(format!("',' or ']' expected at {}", i));
            }
        }
        '"' => {
            *i += 1;
            let mut out = String::new();
            while *i < b.len() {
                let c = b[*i];
                *i += 1;
                match c {
                    '"' => return Ok(Json::Str(out)),
                    '\\' => {
                        if *i >= b.len() { break; }
                        let e = b[*i];
                        *i += 1;
                        match e {
                            'n' => out.push('\n'),
                            't' => out.push('\t'),
                            'r' => out.push('\r'),
                            'b' => out.push('\u{8}'),
                            'f' => out.push('\u{c}'),
                            'u' => {
                                if *i + 4 > b.len() { return Err("bad \\u escape".into()); }
                                let h: String = b[*i..*i + 4].iter().collect();
                                *i += 4;
                                let cp = u32::from_str_radix(&h, 16).map_err(|_| "bad \\u escape".to_string())?;
                                out.push(char::from_u32(cp).unwrap_or('\u{fffd}'));
                            }
                            other => out.push(other),
                        }
                    }
                    c => out.push(c),
                }
            }
            Err("unterminated string".into())
        }
        't' | 'f' | 'n' => {
            for (word, v) in [("true", Json::Bool(true)), ("false", Json::Bool(false)), ("null", Json::Null)] {
                let w: Vec<char> = word.chars().collect();
                if b.len() >= *i + w.len() && b[*i..*i + w.len()] == w[..] { *i += w.len(); return Ok(v); }
            }
            Err(format!("bad literal at {}", i))
        }
        _ => {
            let start = *i;
            while *i < b.len() && (b[*i].is_ascii_digit() || "+-.eE".contains(b[*i])) { *i += 1; }
            let t: String = b[start..*i].iter().collect();
            t.parse::<f64>().map(Json::Num).map_err(|_| format!("bad number '{}' at {}", t, start))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn round_trip_keeps_order_and_escapes() {
        let t = r#"{"b":1,"a":[true,false,null,"x\"y\n",2.5,-3],"c":{}}"#;
        let v = parse(t).unwrap();
        assert_eq!(write(&v), t);
        assert_eq!(v.str("b"), "1");
        assert_eq!(v.arr("a").len(), 6);
        assert!(parse("{").is_err());
        assert!(parse("[1,]x").is_err());
        assert_eq!(parse(r#""A""#).unwrap(), Json::Str("A".into()));
    }
}

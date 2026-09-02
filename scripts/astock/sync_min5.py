import json, os, sqlite3, ssl, sys, urllib.request, urllib.parse, time, random

# ===== A股分钟线(5分钟K线)同步 (独立工作流) =====
# 全市场5分钟线: 新浪主 -> 腾讯备
# 高可用: 频率控制 + 指数退避重试 + 熔断 + 失败清单断点续传
DB_PATH = "/opt/a-stock/data/stock.db"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
CTX = ssl.create_default_context(); CTX.check_hostname=False; CTX.verify_mode=ssl.CERT_NONE

SRC_DELAY = 0.08       # 分钟线数据量大, 稍放宽
RETRY_BASE = 0.8
RETRY_MAX = 3
CIRCUIT_BREAK = 12
CIRCUIT_COOLDOWN = 90
_src_state = {}

def _throttle(src):
    st = _src_state.get(src)
    if st and st.get("broken_until", 0) > time.time():
        raise RuntimeError("circuit_open:" + src)
    if st:
        gap = time.time() - st["last"]
        if gap < SRC_DELAY:
            time.sleep(SRC_DELAY - gap)
    _src_state.setdefault(src, {"last": 0, "fails": 0, "broken_until": 0})["last"] = time.time()

def _record_success(src):
    if src in _src_state: _src_state[src]["fails"] = 0

def _record_fail(src):
    st = _src_state.setdefault(src, {"last": 0, "fails": 0, "broken_until": 0})
    st["fails"] += 1
    if st["fails"] >= CIRCUIT_BREAK:
        st["broken_until"] = time.time() + CIRCUIT_COOLDOWN
        st["fails"] = 0
        print("  [circuit] %s 熔断%d秒" % (src, CIRCUIT_COOLDOWN), file=sys.stderr)

def http_get(url, src, headers=None, timeout=10, encoding="utf-8"):
    _throttle(src)
    rq = urllib.request.Request(url)
    rq.add_header("User-Agent", UA)
    for k,v in (headers or {}).items(): rq.add_header(k,v)
    last=None
    for i in range(RETRY_MAX):
        try:
            with urllib.request.urlopen(rq, timeout=timeout, context=CTX) as r:
                raw = r.read()
                if raw.lstrip().startswith(b"<"):
                    raise RuntimeError("anti-crawl/waf page")
                _record_success(src)
                return raw.decode(encoding, "replace")
        except Exception as e:
            last=e
            if isinstance(e, RuntimeError) and str(e).startswith("circuit_open"):
                raise
            _record_fail(src)
            if i < RETRY_MAX-1:
                time.sleep(RETRY_BASE * (2**i) + random.uniform(0, RETRY_BASE))
    raise last

def market(code):
    return "sh" if code.startswith(("6","9")) else "sz"

def db():
    conn = sqlite3.connect(DB_PATH, timeout=30)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=30000")
    return conn

def is_supported(code):
    return not (code.startswith(("8","4","920")))

# 5分钟线: 新浪主源
def min5_sina(conn, code):
    mk = market(code)
    url = ("https://quotes.sina.cn/cn/api/jsonp_v2.php/var%20_data=/CN_MarketDataService.getKLineData"
           "?symbol=" + mk + code + "&scale=5&ma=no&datalen=700")  # 700根5分钟线~近3交易日
    raw = http_get(url, "sina", {"Referer": "https://finance.sina.com.cn/"})
    lb = raw.find("([")
    rb = raw.rfind("])")
    if lb < 0 or rb < 0 or rb <= lb:
        return 0
    arr = json.loads(raw[lb+1:rb+1])
    n = 0
    for it in arr:
        day = it.get("day")  # "2026-08-27 14:10:00"
        if not day or len(day) < 16: continue
        tt = day[:16]  # "2026-08-27 14:10"
        rows = [(code, tt, _f(it.get("open")), _f(it.get("close")), _f(it.get("high")), _f(it.get("low")), _f(it.get("volume")), _f(it.get("amount")))]
        conn.executemany("INSERT OR REPLACE INTO kline_min5(sec_code,trade_time,open,close,high,low,volume,amount) VALUES(?,?,?,?,?,?,?,?)", rows)
        n += 1
    conn.commit()
    return n

# 5分钟线: 腾讯备源
def min5_tencent(conn, code):
    mk = market(code)
    url = "https://ifzq.gtimg.cn/appstock/app/kline/mkline?param=%s%s,m5,,700" % (mk, code)
    raw = http_get(url, "tencent", {"Referer": "https://gu.qq.com/"})
    d = json.loads(raw)
    node = (d.get("data") or {}).get(mk + code) or {}
    m5 = node.get("m5") or []
    n = 0
    for k in m5:
        if len(k) < 6: continue
        # 腾讯格式: [时间,开,收,高,低,量] 时间如 "202608271410"
        ts = k[0]
        if len(ts) < 12: continue
        tt = "%s-%s-%s %s:%s" % (ts[0:4], ts[4:6], ts[6:8], ts[8:10], ts[10:12])
        conn.execute("INSERT OR REPLACE INTO kline_min5(sec_code,trade_time,open,close,high,low,volume,amount) VALUES(?,?,?,?,?,?,?,0)",
            (code, tt, _f(k[1]), _f(k[2]), _f(k[3]), _f(k[4]), _f(k[5])))
        n += 1
    conn.commit()
    return n

def _f(v):
    try: return float(v)
    except: return None

def sync_min5(conn, code):
    res = {}
    for name, fn in [("sina", min5_sina), ("tencent", min5_tencent)]:
        try:
            res["min5"] = fn(conn, code)
            res["src"] = name
            return (True, res)
        except Exception as e:
            res[name + "_err"] = str(e)[:50]
    return (False, res)

def main():
    conn = db()
    conn.executescript("""
CREATE TABLE IF NOT EXISTS kline_min5(sec_code TEXT, trade_time TEXT, open REAL, close REAL, high REAL, low REAL, volume REAL, amount REAL, PRIMARY KEY(sec_code,trade_time));
CREATE TABLE IF NOT EXISTS sync_fail_log(sec_code TEXT PRIMARY KEY, fail_time TEXT, detail TEXT);
""")
    pending = [r[0] for r in conn.execute("SELECT sec_code FROM sync_fail_log WHERE detail LIKE '%min5%'")]
    allcodes = [r[0] for r in conn.execute("SELECT sec_code FROM stock_pool ORDER BY sec_code")]
    codes = [c for c in allcodes if is_supported(c)]
    limit = os.environ.get("SYNC_LIMIT")
    if limit:
        codes = codes[:int(limit)]
        pending = []
    retry_first = [c for c in pending if c in codes]
    main_codes = [c for c in codes if c not in pending]
    total = len(main_codes) + len(retry_first)
    stats = {"total": total, "ok": 0, "err": 0, "min5_rows": 0, "sources": {"sina":0,"tencent":0}}
    errors = []
    t0 = time.time()
    for code in retry_first + main_codes:
        try:
            ok, res = sync_min5(conn, code)
        except Exception as e:
            ok, res = False, {"fatal": str(e)[:80]}
        if ok:
            stats["ok"] += 1
            conn.execute("DELETE FROM sync_fail_log WHERE sec_code=?", (code,))
            src = res.get("src","?")
            stats["sources"][src] = stats["sources"].get(src,0)+1
        else:
            stats["err"] += 1
            conn.execute("INSERT OR REPLACE INTO sync_fail_log(sec_code, fail_time, detail) VALUES(?,?,?)",
                (code, time.strftime("%Y-%m-%d %H:%M:%S"), json.dumps(res, ensure_ascii=False)[:500]))
            if len(errors) < 10: errors.append(code + ":" + str(res))
        stats["min5_rows"] += res.get("min5", 0)
        conn.commit()
    stats["elapsed_s"] = round(time.time() - t0, 1)
    stats["errors_sample"] = errors
    print(json.dumps(stats, ensure_ascii=False))

if __name__ == "__main__":
    main()

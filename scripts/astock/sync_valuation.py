import json, os, sqlite3, ssl, sys, urllib.request, urllib.parse, time, random

# ===== A股估值同步 (独立工作流B) =====
# 全市场估值: 东财 RPT_VALUEANALYSIS_DET
# 高可用: 频率控制 + 指数退避重试 + 熔断 + 失败清单断点续传
DB_PATH = "/opt/a-stock/data/stock.db"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
CTX = ssl.create_default_context(); CTX.check_hostname=False; CTX.verify_mode=ssl.CERT_NONE

SRC_DELAY = 0.05
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

def db():
    conn = sqlite3.connect(DB_PATH, timeout=30)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=30000")
    return conn

def is_supported(code):
    return not (code.startswith(("8","4","920")))

def sync_valuation(conn, code):
    base = "https://datacenter-web.eastmoney.com/api/data/v1/get?"
    params = {
        "reportName":"RPT_VALUEANALYSIS_DET",
        "columns":"ALL",
        "filter":"(SECURITY_CODE=\"" + code + "\")",
        "pageNumber":"1","pageSize":"5","sortTypes":"-1","sortColumns":"TRADE_DATE",
        "source":"WEB","client":"WEB",
    }
    url = base + urllib.parse.urlencode(params)
    raw = http_get(url, "eastmoney", headers={"Referer":"https://data.eastmoney.com/"})
    d = json.loads(raw)
    rows = (d.get("result") or {}).get("data") or []
    n=0
    for rw in rows:
        conn.execute("INSERT OR REPLACE INTO valuation(sec_code,trade_date,pe_ttm,pb,ps_ttm,total_mv,circ_mv,div_yield) VALUES(?,?,?,?,?,?,?,?)",
            (code, str(rw.get("TRADE_DATE",""))[:10], rw.get("PE_TTM"), rw.get("PB_MRQ"), rw.get("PS_TTM"), rw.get("TOTAL_MARKET_CAP"), rw.get("FREE_MARKET_CAP"), rw.get("DIVIDEND_YIELD_RATIO")))
        n+=1
    conn.commit()
    return n

def main():
    conn = db()
    conn.executescript("""
CREATE TABLE IF NOT EXISTS valuation(sec_code TEXT, trade_date TEXT, pe_ttm REAL, pb REAL, ps_ttm REAL, total_mv REAL, circ_mv REAL, div_yield REAL, PRIMARY KEY(sec_code,trade_date));
CREATE TABLE IF NOT EXISTS sync_fail_log(sec_code TEXT PRIMARY KEY, fail_time TEXT, detail TEXT);
""")
    pending = [r[0] for r in conn.execute("SELECT sec_code FROM sync_fail_log WHERE detail LIKE '%valuation%'")]
    allcodes = [r[0] for r in conn.execute("SELECT sec_code FROM stock_pool ORDER BY sec_code")]
    codes = [c for c in allcodes if is_supported(c)]
    limit = os.environ.get("SYNC_LIMIT")
    if limit:
        codes = codes[:int(limit)]
        pending = []
    retry_first = [c for c in pending if c in codes]
    main_codes = [c for c in codes if c not in pending]
    total = len(main_codes) + len(retry_first)
    stats = {"total": total, "ok": 0, "err": 0, "val_rows": 0}
    errors = []
    t0 = time.time()
    for code in retry_first + main_codes:
        try:
            n = sync_valuation(conn, code)
            stats["ok"] += 1
            conn.execute("DELETE FROM sync_fail_log WHERE sec_code=?", (code,))
            stats["val_rows"] += n
        except Exception as e:
            stats["err"] += 1
            conn.execute("INSERT OR REPLACE INTO sync_fail_log(sec_code, fail_time, detail) VALUES(?,?,?)",
                (code, time.strftime("%Y-%m-%d %H:%M:%S"), json.dumps({"valuation_err": str(e)[:80]})))
            if len(errors) < 10: errors.append(code + ":" + str(e)[:60])
        conn.commit()
    stats["elapsed_s"] = round(time.time() - t0, 1)
    stats["errors_sample"] = errors
    print(json.dumps(stats, ensure_ascii=False))

if __name__ == "__main__":
    main()

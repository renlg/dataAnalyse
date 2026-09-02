import json, os, sqlite3, ssl, sys, urllib.request, urllib.parse, time, random

# ===== A股日线同步 (独立工作流A) =====
# 全市场日线: 东财主 -> 新浪备 -> 腾讯兜底
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

def market(code):
    return "sh" if code.startswith(("6","9")) else "sz"

def secid_eastmoney(code):
    return ("1." if code.startswith(("6","9")) else "0.") + code

def db():
    conn = sqlite3.connect(DB_PATH, timeout=30)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=30000")
    return conn

def is_supported(code):
    return not (code.startswith(("8","4","920")))

def daily_eastmoney(conn, code):
    sid = secid_eastmoney(code)
    beg = time.strftime("%Y%m%d", time.localtime(time.time()-240*86400))
    end = time.strftime("%Y%m%d")
    url = ("https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=" + sid +
           "&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58"
           "&klt=101&fqt=1&beg=" + beg + "&end=" + end)
    raw = http_get(url, "eastmoney", {"Referer": "https://quote.eastmoney.com/"})
    d = json.loads(raw)
    klines = ((d.get("data") or {}).get("klines")) or []
    rows = []
    for k in klines:
        p = k.split(",")
        if len(p) < 7: continue
        rows.append([p[0], p[1], p[2], p[3], p[4], p[5]])
    return _write_daily(conn, code, rows)

def daily_sina(conn, code):
    mk = market(code)
    url = ("https://quotes.sina.cn/cn/api/jsonp_v2.php/var%20_data=/CN_MarketDataService.getKLineData"
           "?symbol=" + mk + code + "&scale=240&ma=no&datalen=250")
    raw = http_get(url, "sina", {"Referer": "https://finance.sina.com.cn/"})
    lb = raw.find("([")
    rb = raw.rfind("])")
    if lb < 0 or rb < 0 or rb <= lb:
        return 0
    arr = json.loads(raw[lb+1:rb+1])
    rows = []
    for it in arr:
        rows.append([it.get("day"), it.get("open"), it.get("close"), it.get("high"), it.get("low"), it.get("volume")])
    return _write_daily(conn, code, rows)

def daily_tencent(conn, code):
    mk = market(code)
    url = "https://ifzq.gtimg.cn/appstock/app/fqkline/get?param=%s%s,day,,,250,qfq" % (mk, code)
    raw = http_get(url, "tencent", {"Referer": "https://gu.qq.com/"})
    d = json.loads(raw)
    node = (d.get("data") or {}).get(mk + code) or {}
    kl = node.get("qfqday") or node.get("day") or []
    return _write_daily(conn, code, kl)

def _write_daily(conn, code, kl):
    n = 0
    for k in kl:
        if len(k) < 6: continue
        td=k[0]; o=_f(k[1]); cl=_f(k[2]); h=_f(k[3]); lo=_f(k[4]); v=_f(k[5])
        if td is None or cl is None: continue
        prev=None
        cur=conn.execute("SELECT close FROM kline_daily WHERE sec_code=? AND trade_date<? ORDER BY trade_date DESC LIMIT 1",(code,td))
        row=cur.fetchone()
        if row: prev=row[0]
        pct=round((cl-prev)/prev*100,4) if prev else None
        conn.execute("INSERT OR REPLACE INTO kline_daily(sec_code,trade_date,open,close,high,low,volume,amount,pct_chg) VALUES(?,?,?,?,?,?,?,?,?)",
            (code,td,o,cl,h,lo,v,0,pct))
        n+=1
    conn.commit()
    return n

def _f(v):
    try: return float(v)
    except: return None

def sync_daily(conn, code):
    res = {}
    for name, fn in [("eastmoney", daily_eastmoney), ("sina", daily_sina), ("tencent", daily_tencent)]:
        try:
            res["daily"] = fn(conn, code)
            res["src"] = name
            return (True, res)
        except Exception as e:
            res[name + "_err"] = str(e)[:50]
    return (False, res)

def main():
    conn = db()
    conn.executescript("""
CREATE TABLE IF NOT EXISTS kline_daily(sec_code TEXT, trade_date TEXT, open REAL, close REAL, high REAL, low REAL, volume REAL, amount REAL, pct_chg REAL, PRIMARY KEY(sec_code,trade_date));
CREATE TABLE IF NOT EXISTS sync_fail_log(sec_code TEXT PRIMARY KEY, fail_time TEXT, detail TEXT);
""")
    pending = [r[0] for r in conn.execute("SELECT sec_code FROM sync_fail_log WHERE detail LIKE '%daily%'")]
    allcodes = [r[0] for r in conn.execute("SELECT sec_code FROM stock_pool ORDER BY sec_code")]
    codes = [c for c in allcodes if is_supported(c)]
    # 测试模式: 环境变量 SYNC_LIMIT 限制数量 (默认全量)
    limit = os.environ.get("SYNC_LIMIT")
    if limit:
        codes = codes[:int(limit)]
        pending = []
    retry_first = [c for c in pending if c in codes]
    main_codes = [c for c in codes if c not in pending]
    total = len(main_codes) + len(retry_first)
    stats = {"total": total, "ok": 0, "err": 0, "daily_rows": 0, "sources": {"eastmoney":0,"sina":0,"tencent":0}}
    errors = []
    t0 = time.time()
    for code in retry_first + main_codes:
        try:
            ok, res = sync_daily(conn, code)
        except Exception as e:
            ok, res = False, {"fatal": str(e)[:80]}
        if ok:
            stats["ok"] += 1
            conn.execute("DELETE FROM sync_fail_log WHERE sec_code=?", (code,))
            stats["sources"][res.get("src","?")] = stats["sources"].get(res.get("src","?"),0)+1
        else:
            stats["err"] += 1
            conn.execute("INSERT OR REPLACE INTO sync_fail_log(sec_code, fail_time, detail) VALUES(?,?,?)",
                (code, time.strftime("%Y-%m-%d %H:%M:%S"), json.dumps(res, ensure_ascii=False)[:500]))
            if len(errors) < 10: errors.append(code + ":" + str(res))
        stats["daily_rows"] += res.get("daily", 0)
        conn.commit()
    stats["elapsed_s"] = round(time.time() - t0, 1)
    stats["errors_sample"] = errors
    print(json.dumps(stats, ensure_ascii=False))

if __name__ == "__main__":
    main()

import json, os, sqlite3, ssl, sys, urllib.request, urllib.parse, time, random, threading
from datetime import datetime, date

# ===== A股分钟线(5分钟K线)同步 (参数化版本) =====
# 支持: 自选股高频 / 全市场低频 / 全量 三种模式
# 数据源: 新浪主 -> 腾讯备
# 高可用: 频率控制 + 指数退避重试 + 熔断 + 失败清单断点续传

# ----- 运行模式配置 (环境变量覆盖, 工作流节点中直接改常量) -----
SYNC_SOURCE = os.environ.get("SYNC_SOURCE", "all")
#   watch     = 只拉模拟盘自选股
#   all       = 全市场(排除自选, 避免与高频流程重复)
#   all_full  = 全市场(含自选, 备用全量)
SYNC_WORKERS = int(os.environ.get("SYNC_WORKERS", "8"))   # 并发线程数, 1=串行
SYNC_ONLY_TODAY = os.environ.get("SYNC_ONLY_TODAY", "0") == "1"
#   1 = 拉取近N根K线但只写入当天日期的行 (全市场低频模式)
#   0 = 增量模式, 只拉最新2根 (自选高频模式)

WATCH_DB = "/opt/stock-trading/data/stock.db"
DB_PATH = "/opt/a-stock/data/stock.db"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
CTX = ssl.create_default_context(); CTX.check_hostname=False; CTX.verify_mode=ssl.CERT_NONE

SRC_DELAY = 0.08
RETRY_BASE = 0.8
RETRY_MAX = 3
CIRCUIT_BREAK = 12
CIRCUIT_COOLDOWN = 90
_src_state = {}
_src_lock = threading.Lock()

# 2026年法定节假日(非交易日)
_HOLIDAYS_2026 = {
    date(2026,1,1), date(2026,1,2), date(2026,1,3),                                          # 元旦
    date(2026,2,16), date(2026,2,17), date(2026,2,18), date(2026,2,19), date(2026,2,20),     # 春节
    date(2026,2,23), date(2026,2,24),
    date(2026,4,4), date(2026,4,5), date(2026,4,6),                                           # 清明
    date(2026,5,1), date(2026,5,2), date(2026,5,3), date(2026,5,4), date(2026,5,5),          # 五一
    date(2026,6,19), date(2026,6,20), date(2026,6,21),                                        # 端午
    date(2026,9,25), date(2026,9,26), date(2026,9,27),                                        # 中秋
    date(2026,10,1), date(2026,10,2), date(2026,10,3), date(2026,10,4), date(2026,10,5),     # 国庆
    date(2026,10,6), date(2026,10,7),
}

def is_trading_day(d=None):
    if d is None:
        d = date.today()
    if d.weekday() >= 5:
        return False
    if d.year == 2026 and d in _HOLIDAYS_2026:
        return False
    return True

def _get_watch_codes():
    """读模拟盘自选股 DISTINCT code"""
    try:
        wconn = sqlite3.connect(WATCH_DB, timeout=10)
        codes = [r[0] for r in wconn.execute("SELECT DISTINCT code FROM watch_stocks").fetchall()]
        wconn.close()
        return set(codes)
    except Exception as e:
        print("ERROR: 无法读取模拟盘自选股 %s: %s" % (WATCH_DB, e), file=sys.stderr)
        sys.exit(1)

def get_stock_codes():
    """根据 SYNC_SOURCE 返回股票列表"""
    if SYNC_SOURCE == "watch":
        codes = _get_watch_codes()
        return sorted(c for c in codes if is_supported(c))
    watch = _get_watch_codes() if SYNC_SOURCE == "all" else set()
    conn = db()
    allcodes = [r[0] for r in conn.execute("SELECT sec_code FROM stock_pool ORDER BY sec_code")]
    conn.close()
    if SYNC_SOURCE == "all":
        return [c for c in allcodes if is_supported(c) and c not in watch]
    return [c for c in allcodes if is_supported(c)]

def _throttle(src):
    with _src_lock:
        st = _src_state.get(src)
        if st and st.get("broken_until", 0) > time.time():
            raise RuntimeError("circuit_open:" + src)
        if st:
            gap = time.time() - st["last"]
            if gap < SRC_DELAY:
                time.sleep(SRC_DELAY - gap)
        _src_state.setdefault(src, {"last": 0, "fails": 0, "broken_until": 0})["last"] = time.time()

def _record_success(src):
    with _src_lock:
        if src in _src_state: _src_state[src]["fails"] = 0

def _record_fail(src):
    with _src_lock:
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

def _f(v):
    try: return float(v)
    except: return None

# 5分钟线: 新浪主源
def min5_sina(conn, code):
    mk = market(code)
    datalen = 100 if SYNC_ONLY_TODAY else 2
    url = ("https://quotes.sina.cn/cn/api/jsonp_v2.php/var%20_data=/CN_MarketDataService.getKLineData"
           "?symbol=" + mk + code + "&scale=5&ma=no&datalen=%d" % datalen)
    raw = http_get(url, "sina", {"Referer": "https://finance.sina.com.cn/"})
    lb = raw.find("([")
    rb = raw.rfind("])")
    if lb < 0 or rb <= lb:
        return 0
    arr = json.loads(raw[lb+1:rb+1])
    today_str = date.today().isoformat()
    n = 0
    for it in arr:
        day = it.get("day")
        if not day or len(day) < 16: continue
        tt = day[:16]
        if SYNC_ONLY_TODAY and not tt.startswith(today_str):
            continue
        conn.execute("INSERT OR REPLACE INTO kline_min5(sec_code,trade_time,open,close,high,low,volume,amount) VALUES(?,?,?,?,?,?,?,?)",
            (code, tt, _f(it.get("open")), _f(it.get("close")), _f(it.get("high")), _f(it.get("low")), _f(it.get("volume")), _f(it.get("amount"))))
        n += 1
    conn.commit()
    return n

# 5分钟线: 腾讯备源
def min5_tencent(conn, code):
    mk = market(code)
    datalen = 100 if SYNC_ONLY_TODAY else 2
    url = "https://ifzq.gtimg.cn/appstock/app/kline/mkline?param=%s%s,m5,,%d" % (mk, code, datalen)
    raw = http_get(url, "tencent", {"Referer": "https://gu.qq.com/"})
    d = json.loads(raw)
    node = (d.get("data") or {}).get(mk + code) or {}
    m5 = node.get("m5") or []
    today_str = date.today().isoformat()
    n = 0
    for k in m5:
        if len(k) < 6: continue
        ts = k[0]
        if len(ts) < 12: continue
        tt = "%s-%s-%s %s:%s" % (ts[0:4], ts[4:6], ts[6:8], ts[8:10], ts[10:12])
        if SYNC_ONLY_TODAY and not tt.startswith(today_str):
            continue
        conn.execute("INSERT OR REPLACE INTO kline_min5(sec_code,trade_time,open,close,high,low,volume,amount) VALUES(?,?,?,?,?,?,?,0)",
            (code, tt, _f(k[1]), _f(k[2]), _f(k[3]), _f(k[4]), _f(k[5])))
        n += 1
    conn.commit()
    return n

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
    if not is_trading_day():
        print(json.dumps({"skipped": True, "reason": "non_trading_day", "date": str(date.today())}))
        return
    codes = get_stock_codes()
    conn = db()
    conn.executescript("""
CREATE TABLE IF NOT EXISTS kline_min5(sec_code TEXT, trade_time TEXT, open REAL, close REAL, high REAL, low REAL, volume REAL, amount REAL, PRIMARY KEY(sec_code,trade_time));
CREATE TABLE IF NOT EXISTS sync_fail_log(sec_code TEXT PRIMARY KEY, fail_time TEXT, detail TEXT);
""")
    pending = [r[0] for r in conn.execute("SELECT sec_code FROM sync_fail_log WHERE detail LIKE '%min5%'")]
    limit = os.environ.get("SYNC_LIMIT")
    if limit:
        codes = codes[:int(limit)]
        pending = []
    codes_set = set(codes)
    retry_first = [c for c in pending if c in codes_set]
    main_codes = [c for c in codes if c not in codes_set.intersection(pending)]
    all_codes = retry_first + main_codes
    total = len(all_codes)
    stats = {"total": total, "ok": 0, "err": 0, "min5_rows": 0,
             "sources": {"sina":0,"tencent":0}, "mode": SYNC_SOURCE, "workers": SYNC_WORKERS}
    errors = []
    fail_records = []
    success_codes = []
    stats_lock = threading.Lock()
    t0 = time.time()

    def worker(code):
        wconn = db()
        try:
            ok, res = sync_min5(wconn, code)
        except Exception as e:
            ok, res = False, {"fatal": str(e)[:80]}
        finally:
            wconn.close()
        with stats_lock:
            if ok:
                stats["ok"] += 1
                success_codes.append(code)
                src = res.get("src","?")
                stats["sources"][src] = stats["sources"].get(src,0)+1
            else:
                stats["err"] += 1
                fail_records.append((code, time.strftime("%Y-%m-%d %H:%M:%S"), json.dumps(res, ensure_ascii=False)[:500]))
                if len(errors) < 10: errors.append(code + ":" + str(res))
            stats["min5_rows"] += res.get("min5", 0)

    from concurrent.futures import ThreadPoolExecutor, as_completed
    workers = max(1, SYNC_WORKERS)
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(worker, c): c for c in all_codes}
        for f in as_completed(futures):
            pass

    if success_codes:
        conn.executemany("DELETE FROM sync_fail_log WHERE sec_code=?", [(c,) for c in success_codes])
    if fail_records:
        conn.executemany("INSERT OR REPLACE INTO sync_fail_log(sec_code,fail_time,detail) VALUES(?,?,?)", fail_records)
    conn.commit()
    conn.close()
    stats["elapsed_s"] = round(time.time() - t0, 1)
    stats["errors_sample"] = errors
    print(json.dumps(stats, ensure_ascii=False))

if __name__ == "__main__":
    main()

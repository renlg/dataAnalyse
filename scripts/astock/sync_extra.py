# -*- coding: utf-8 -*-
import json, os, sqlite3, ssl, sys, time, urllib.request, urllib.parse, datetime, random

# ===== A股补充数据同步: 一致预期 + 两融 + 北向资金流 (独立工作流) =====
# 高可用: 频率控制 + 指数退避重试 + 熔断 + 失败清单断点续传
# 日期动态计算最近交易日, 避免原脚本硬编码日期bug
DB_PATH = "/opt/a-stock/data/stock.db"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
CTX = ssl.create_default_context(); CTX.check_hostname=False; CTX.verify_mode=ssl.CERT_NONE

SRC_DELAY = 0.12
RETRY_BASE = 0.8
RETRY_MAX = 3
CIRCUIT_BREAK = 10
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

def http_get(url, src, headers=None, timeout=15, encoding="utf-8"):
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

BASE = "https://datacenter-web.eastmoney.com/api/data/v1/get?"
H = {"Referer":"https://data.eastmoney.com/"}

def db():
    conn = sqlite3.connect(DB_PATH, timeout=30)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=30000")
    return conn

def init(conn):
    conn.executescript("""
    CREATE TABLE IF NOT EXISTS consensus (
        sec_code TEXT PRIMARY KEY, sec_name TEXT,
        rating_org_num INTEGER, rating_buy INTEGER, rating_add INTEGER,
        rating_neutral INTEGER, rating_reduce INTEGER, rating_sale INTEGER,
        eps1 REAL, year1 INTEGER, eps2 REAL, year2 INTEGER,
        eps3 REAL, year3 INTEGER, eps4 REAL, year4 INTEGER,
        aimprice_max REAL, aimprice_min REAL, fetch_date TEXT
    );
    CREATE TABLE IF NOT EXISTS margin (
        sec_code TEXT, trade_date TEXT, sec_name TEXT, market TEXT,
        rz_balance REAL, rq_volume REAL, rzrq_balance REAL, rq_balance REAL,
        rq_mcl REAL, rzrq_chg REAL, PRIMARY KEY (sec_code, trade_date)
    );
    CREATE TABLE IF NOT EXISTS northbound (
        trade_date TEXT PRIMARY KEY, net_inflow_sh REAL, net_inflow_sz REAL,
        net_inflow_both REAL, buy_amt REAL, sell_amt REAL,
        net_deal_amt REAL, accum_deal_amt REAL
    );
    CREATE TABLE IF NOT EXISTS sync_fail_log(sec_code TEXT PRIMARY KEY, fail_time TEXT, detail TEXT);
    """)
    conn.commit()

# 最近交易日 (跳过周末)
def recent_trade_days(n):
    days = []
    d = datetime.date.today()
    while len(days) < n:
        if d.weekday() < 5:
            days.append(d.strftime("%Y-%m-%d"))
        d -= datetime.timedelta(days=1)
    return days

def fetch_consensus(conn):
    n=0; page=1
    while True:
        q = urllib.parse.urlencode({"reportName":"RPT_WEB_RESPREDICT","columns":"ALL",
            "pageNumber":str(page),"pageSize":"500","source":"WEB","client":"WEB"})
        j = json.loads(http_get(BASE+q, "eastmoney", H))
        data = (j.get("result") or {}).get("data") or []
        if not data: break
        today = time.strftime("%Y-%m-%d")
        for d in data:
            code = d.get("SECURITY_CODE")
            if not code: continue
            conn.execute("""INSERT OR REPLACE INTO consensus
                (sec_code, sec_name, rating_org_num, rating_buy, rating_add, rating_neutral,
                 rating_reduce, rating_sale, eps1, year1, eps2, year2, eps3, year3,
                 eps4, year4, aimprice_max, aimprice_min, fetch_date)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (code, d.get("SECURITY_NAME_ABBR"), d.get("RATING_ORG_NUM"),
                 d.get("RATING_BUY_NUM"), d.get("RATING_ADD_NUM"), d.get("RATING_NEUTRAL_NUM"),
                 d.get("RATING_REDUCE_NUM"), d.get("RATING_SALE_NUM"),
                 d.get("EPS1"), d.get("YEAR1"), d.get("EPS2"), d.get("YEAR2"),
                 d.get("EPS3"), d.get("YEAR3"), d.get("EPS4"), d.get("YEAR4"),
                 d.get("DEC_AIMPRICEMAX"), d.get("DEC_AIMPRICEMIN"), today))
            n+=1
        if len(data) < 500: break
        page+=1; time.sleep(0.4)
    conn.commit()
    return n

def fetch_margin_day(conn, day):
    n=0; page=1
    while True:
        q = urllib.parse.urlencode({"reportName":"RPTA_WEB_RZRQ_GGMX","columns":"ALL",
            "filter":"(DATE='%s')"%day,"pageNumber":str(page),"pageSize":"500",
            "source":"WEB","client":"WEB"})
        j = json.loads(http_get(BASE+q, "eastmoney", H))
        data = (j.get("result") or {}).get("data") or []
        if not data: break
        for d in data:
            code = d.get("SCODE")
            if not code: continue
            conn.execute("""INSERT OR REPLACE INTO margin
                (sec_code, trade_date, sec_name, market, rz_balance, rq_volume, rzrq_balance,
                 rq_balance, rq_mcl, rzrq_chg)
                VALUES (?,?,?,?,?,?,?,?,?,?)""",
                (code, d.get("DATE","")[:10], d.get("SECNAME"), d.get("MARKET"),
                 d.get("RZYE"), d.get("RQYL"), d.get("RZRQYE"),
                 d.get("RQYE"), d.get("RQMCL"), d.get("RZRQYECZ")))
            n+=1
        if len(data) < 500: break
        page+=1; time.sleep(0.3)
    conn.commit()
    return n

def fetch_north_flow(conn, day):
    n=0
    q = urllib.parse.urlencode({"reportName":"RPT_MUTUAL_NETINFLOW_DETAILS","columns":"ALL",
        "filter":"(TRADE_DATE='%s')"%day,"pageNumber":"1","pageSize":"10","source":"WEB","client":"WEB"})
    j = json.loads(http_get(BASE+q, "eastmoney", H))
    data = (j.get("result") or {}).get("data") or []
    for d in data:
        if d.get("TIME_TYPE")=="1" and d.get("DIRECTION_TYPE")=="2":
            conn.execute("""INSERT OR REPLACE INTO northbound
                (trade_date, net_inflow_sh, net_inflow_sz, net_inflow_both)
                VALUES (?,?,?,?)""",
                (d.get("TRADE_DATE","")[:10], d.get("NET_INFLOW_SH"),
                 d.get("NET_INFLOW_SZ"), d.get("NET_INFLOW_BOTH")))
            n+=1; break
    conn.commit()
    return n

def main():
    conn = db(); init(conn)
    stats = {"consensus":0, "margin_days":0, "margin_rows":0, "north_days":0, "north_rows":0}
    errors = []
    t0 = time.time()

    # 1. 一致预期
    try:
        n = fetch_consensus(conn)
        stats["consensus"] = n
    except Exception as e:
        errors.append("consensus:" + str(e)[:80])

    # 2. 两融: 最近3个交易日
    margin_days = recent_trade_days(3)
    stats["margin_days"] = len(margin_days)
    for day in margin_days:
        try:
            n = fetch_margin_day(conn, day)
            stats["margin_rows"] += n
        except Exception as e:
            errors.append("margin_%s:%s" % (day, str(e)[:60]))

    # 3. 北向资金流: 最近2个交易日
    flow_days = recent_trade_days(2)
    stats["north_days"] = len(flow_days)
    for day in flow_days:
        try:
            n = fetch_north_flow(conn, day)
            stats["north_rows"] += n
        except Exception as e:
            errors.append("north_%s:%s" % (day, str(e)[:60]))

    stats["elapsed_s"] = round(time.time() - t0, 1)
    stats["errors_sample"] = errors
    conn.close()
    print(json.dumps(stats, ensure_ascii=False))

if __name__ == "__main__":
    main()

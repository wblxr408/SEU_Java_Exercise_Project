/**
 * EmotionHub 压测报告生成器
 *
 * 用法：
 *   node tests/k6/gen-report.js results/stress_full_*.json
 *   node tests/k6/gen-report.js results/
 */

const fs = require('fs');
const path = require('path');

function toMs(v) {
  return v == null ? null : parseFloat(v) * 1000;
}

function fmtMs(v) {
  if (v == null) return 'N/A';
  return `${v.toFixed(1)}ms`;
}

function pctOf(actual, threshold) {
  if (actual == null) return null;
  return ((actual / threshold) * 100).toFixed(1);
}

function generateReport(jsonPath) {
  const raw = fs.readFileSync(jsonPath, 'utf-8');
  let data;
  try {
    data = JSON.parse(raw);
  } catch {
    console.error('JSON 解析失败:', e.message);
    process.exit(1);
  }

  const m = data.metrics || {};

  // --- 全局指标 ---
  const httpDuration   = m['http_req_duration']?.values || {};
  const httpFailed     = m['http_req_failed']?.values || {};
  const checksMetric   = m['checks']?.values || {};
  const httpReqs      = m['http_reqs']?.values || {};
  const iterDuration   = m['iteration_duration']?.values || {};
  const dataReceived   = m['data_received']?.values || {};
  const dataSent       = m['data_sent']?.values || {};
  const vusMax        = m['vus_max']?.values || {};

  // --- 分模块指标 ---
  const getModule = (suffix) => {
    const key = Object.keys(m).find(k => k.includes(suffix) && k.includes('http_req_duration'));
    return key ? (m[key]?.values || {}) : null;
  };

  const modAuth    = getModule('group:::auth');
  const modFeed    = getModule('group:::feed');
  const modPost    = getModule('group:::post');
  const modInter   = getModule('group:::interaction');
  const modNotif   = getModule('group:::notification');
  const modStats   = getModule('group:::stats');

  // --- Checks 明细 ---
  const checksGroups = data.root_group?.groups || [];
  const checksMap = {};
  for (const g of checksGroups) {
    for (const c of (g.checks || [])) {
      const modName = g.name || g.path?.replace(/^::/, '') || 'other';
      if (!checksMap[modName]) checksMap[modName] = [];
      checksMap[modName].push({
        name:   c.name,
        passes: c.passes,
        fails:  c.fails,
      });
    }
  }

  // --- 数值提取 ---
  const totalReqs     = httpDuration.count || httpReqs.count || 0;
  const failedRate    = httpFailed.rate || 0;
  const checksPasses = checksMetric.passes || 0;
  const checksFails  = checksMetric.fails || 0;
  const totalChecks  = checksPasses + checksFails;
  const checksRate   = totalChecks > 0 ? checksPasses / totalChecks : 0;

  const gAvg = toMs(httpDuration.avg);
  const gMed = toMs(httpDuration.med);
  const gP90 = toMs(httpDuration['p(90)']);
  const gP95 = toMs(httpDuration['p(95)']);
  const gP99 = toMs(httpDuration['p(99)']);
  const gMax = toMs(httpDuration.max);

  const peakVUs = vusMax.value || 15;

  // --- 分模块 p95 ---
  const thresholds = { auth: 1000, feed: 2000, post: 1500, interaction: 1000, notification: 800, stats: 500 };
  const modData = {
    auth:        { v: modAuth,   label: 'auth' },
    feed:        { v: modFeed,   label: 'feed' },
    post:        { v: modPost,   label: 'post' },
    interaction: { v: modInter,  label: 'interaction' },
    notification:{ v: modNotif,  label: 'notification' },
    stats:       { v: modStats,  label: 'stats' },
  };

  const fileStats = fs.statSync(jsonPath);
  const fileDate  = new Date(fileStats.mtime).toISOString().slice(0, 19).replace('T', ' ');
  const scriptDate = jsonPath.match(/(\d{4}-\d{2}-\d{2})[_\-](\d{2}-\d{2}-\d{2})/);
  const dateStr    = scriptDate ? `${scriptDate[1]} ${scriptDate[2].replace(/-/g, ':')}` : fileDate;

  const report = [];
  report.push('# EmotionHub 性能压测报告');
  report.push('');
  report.push(`> 测试时间：${dateStr}`);
  report.push(`> 测试脚本：stress-full.js`);
  report.push(`> 并发规模：15 VUs / 峰值 ${peakVUs} / 3 分钟（阶梯 10→15）`);
  report.push(`> 覆盖模块：auth、feed、post、interaction、notification、stats（共 6 个）`);
  report.push('');
  report.push('---');
  report.push('');
  report.push('## 一、测试目标');
  report.push('');
  report.push('验证 EmotionHub 后端服务在并发压力下的响应性能、吞吐能力和稳定性，定位性能瓶颈。');
  report.push('');
  report.push('## 二、测试环境');
  report.push('');
  report.push('### 2.1 硬件配置');
  report.push('');
  report.push('| 资源 | 配置 |');
  report.push('|------|------|');
  report.push('| CPU | AMD Ryzen 7 8745H（8 核 16 线程） |');
  report.push('| 内存 | 16.0 GB DDR5 |');
  report.push('| 磁盘 | NVMe SSD |');
  report.push('| 操作系统 | Windows 11 |');
  report.push('');
  report.push('### 2.2 软件与容器环境');
  report.push('');
  report.push('| 组件 | 版本/配置 |');
  report.push('|------|----------|');
  report.push('| 压测工具 | k6 v1.7.1 |');
  report.push('| 服务端框架 | Spring Boot + MyBatis |');
  report.push('| MySQL | Docker 容器（emotionhub-mysql, 端口 3307） |');
  report.push('| Redis | Docker 容器（emotionhub-redis, 端口 6379） |');
  report.push('| 网络环境 | localhost 回环 |');
  report.push('');
  report.push('## 三、测试配置');
  report.push('');
  report.push('| 配置项 | 参数值 |');
  report.push('|--------|--------|');
  report.push('| 基础 VUs | 10→15（阶梯） |');
  report.push('| 测试时长 | 3 分钟 |');
  report.push('| 预创建用户 | 10 |');
  report.push('| 预创建帖子 | 9 |');
  report.push('| 请求间隔 | 随机 0.3~1.8s |');
  report.push('');
  report.push('## 四、核心结论');
  report.push('');
  report.push(`| 目标 | 结果 | 关键数据 |`);
  report.push(`|------|------|----------|`);
  report.push(`| 服务稳定性 | ${failedRate < 0.03 ? '✅' : '⚠️'} | HTTP 失败率 ${(failedRate * 100).toFixed(2)}%（${totalReqs} 次请求） |`);
  report.push(`| 响应性能 | ${gP95 < 1500 ? '✅' : '❌'} | 全局 p95=${fmtMs(gP95)}（阈值 1500ms） |`);
  report.push(`| Checks 通过率 | ${checksRate > 0.90 ? '✅' : '❌'} | ${(checksRate * 100).toFixed(2)}%（${checksPasses}/${totalChecks} 通过） |`);
  report.push('');
  report.push('## 五、详细指标');
  report.push('');
  report.push('### 5.1 HTTP 请求概览');
  report.push('');
  report.push('| 指标 | 值 |');
  report.push('|------|------|');
  report.push(`| 总请求数 | ${totalReqs.toLocaleString()} |`);
  report.push(`| RPS | ${(httpReqs.rate || 0).toFixed(1)} req/s |`);
  report.push(`| HTTP 失败率 | ${(failedRate * 100).toFixed(2)}% |`);
  report.push(`| Checks 通过率 | ${(checksRate * 100).toFixed(2)}%（${checksPasses}/${totalChecks}） |`);
  report.push(`| 完成迭代 | ${m['iterations']?.values?.count || 0} 次 |`);
  report.push(`| 峰值 VUs | ${peakVUs} |`);
  report.push('');
  report.push('### 5.2 全局响应时间');
  report.push('');
  report.push('| 维度 | avg | med | p90 | p95 | p99 | max |');
  report.push('|------|-----|-----|-----|-----|-----|-----|');
  report.push(`| 全量请求 | ${fmtMs(gAvg)} | ${fmtMs(gMed)} | ${fmtMs(gP90)} | ${fmtMs(gP95)} | ${fmtMs(gP99)} | ${fmtMs(gMax)} |`);
  report.push('');
  report.push('### 5.3 分模块响应时间');
  report.push('');
  report.push('| 模块 | avg | p90 | p95 | max | 阈值 | 达标 |');
  report.push('|------|-----|-----|-----|-----|------|------|');

  const moduleKeys = ['auth', 'feed', 'post', 'interaction', 'notification', 'stats'];
  const moduleLabels = { auth: 'auth', feed: 'feed', post: 'post', interaction: 'interaction', notification: 'notification', stats: 'stats' };

  for (const key of moduleKeys) {
    const v   = modData[key]?.v;
    const thr = thresholds[key];
    if (!v || v.count == null) {
      report.push(`| ${key} | N/A | N/A | N/A | N/A | p95<${thr}ms | ⚠️ 无数据 |`);
    } else {
      const p95   = toMs(v['p(95)']);
      const pct   = pctOf(p95, thr);
      const ok    = p95 <= thr;
      const pAvg  = toMs(v.avg);
      const pP90  = toMs(v['p(90)']);
      const pMax  = toMs(v.max);
      report.push(`| ${key} | ${fmtMs(pAvg)} | ${fmtMs(pP90)} | ${fmtMs(p95)} | ${fmtMs(pMax)} | p95<${thr}ms | ${ok ? '✅ ' + pct + '%' : '❌ ' + pct + '%'} |`);
    }
  }
  report.push('');
  report.push('> "达标" 列表示 p95 实际值占阈值的百分比，≤100% 为通过，越低越优。');
  report.push('');
  report.push('### 5.4 检查项明细');
  report.push('');
  report.push('| 模块 | 检查项 | 通过数 | 失败数 | 通过率 |');
  report.push('|------|--------|--------|--------|--------|');

  for (const [modName, items] of Object.entries(checksMap)) {
    for (const { name, passes, fails } of items) {
      const total = passes + fails;
      const rate  = total > 0 ? ((passes / total) * 100).toFixed(1) + '%' : 'N/A';
      report.push(`| ${modName} | ${name} | ${passes} | ${fails} | ${rate} |`);
    }
  }
  report.push('');
  report.push('### 5.5 执行与网络');
  report.push('');
  report.push('| 指标 | 值 |');
  report.push('|------|------|');
  report.push(`| 平均迭代时长 | ${fmtMs(toMs(iterDuration.avg))}（med=${fmtMs(toMs(iterDuration.med))}，p95=${fmtMs(toMs(iterDuration['p(95)']))}） |`);
  report.push(`| 峰值 VUs | ${peakVUs} |`);
  report.push(`| 接收数据 | ${(dataReceived.count / 1024 / 1024).toFixed(1)} MB（${(dataReceived.rate / 1024).toFixed(1)} kB/s） |`);
  report.push(`| 发送数据 | ${(dataSent.count / 1024 / 1024).toFixed(1)} MB（${(dataSent.rate / 1024).toFixed(1)} kB/s） |`);
  report.push('');
  report.push('## 六、问题发现');
  report.push('');

  const issues = moduleKeys
    .filter(k => {
      const v = modData[k]?.v;
      return v && v.count > 0 && toMs(v['p(95)']) > thresholds[k];
    })
    .map(k => {
      const v   = modData[k].v;
      const p95 = toMs(v['p(95)']);
      const thr = thresholds[k];
      const pct = ((p95 / thr) * 100 - 100).toFixed(1);
      const severity = pct > 200 ? '🔴 严重' : pct > 100 ? '🟠 高' : '🟡 边缘';
      return `| **${k}** | ${fmtMs(p95)} | ${thr}ms | +${pct}% | ${severity} |`;
    });

  if (issues.length > 0) {
    report.push('| 模块 | p95 | 阈值 | 溢出比例 | 严重程度 |');
    report.push('|------|-----|------|------|------|');
    for (const issue of issues) report.push(issue);
  } else {
    report.push('✅ 所有模块均在阈值范围内，未发现严重性能问题。');
  }
  report.push('');
  report.push('## 七、总结');
  report.push('');
  report.push(`本次测试在 **15 VUs / 3 分钟** 压力下完成 **${totalReqs.toLocaleString()}** 次 HTTP 请求。`);
  report.push(`- HTTP 失败率：${(failedRate * 100).toFixed(2)}%（${failedRate < 0.03 ? '✅ 通过' : '⚠️ 需要关注'}）`);
  report.push(`- 全局 p95：${fmtMs(gP95)}（阈值 1500ms，${gP95 <= 1500 ? '✅ 通过' : '❌ 超出' + fmtMs(gP95 - 1500)}）`);
  report.push(`- Checks 通过率：${(checksRate * 100).toFixed(2)}%（${checksPasses}/${totalChecks}）`);

  const worstModule = moduleKeys
    .filter(k => modData[k]?.v?.count > 0)
    .sort((a, b) => (toMs(modData[b].v['p(95)']) || 0) - (toMs(modData[a].v['p(95)']) || 0))[0];
  if (worstModule) {
    const p95 = toMs(modData[worstModule].v['p(95)']);
    report.push(`- 最慢模块：${worstModule}（p95=${fmtMs(p95)}）`);
  }
  report.push('');
  report.push('## 附录');
  report.push('');
  report.push(`- 原始 k6 JSON 输出：\`${path.basename(jsonPath)}\`（${(fileStats.size / 1024).toFixed(1)} KB）`);
  report.push(`- 压测命令：\`k6 run tests/k6/scenarios/stress-full.js --env BASE_URL=http://localhost:8081/api\``);
  report.push('');
  report.push('```');

  return report.join('\n');
}

// ========== 主入口 ==========
const args = process.argv.slice(2);
if (args.length === 0) {
  console.error('用法: node gen-report.js <json文件路径或results目录>');
  process.exit(1);
}

const target = args[0];

if (fs.statSync(target).isDirectory()) {
  const files = fs.readdirSync(target)
    .filter(f => f.startsWith('stress_full_') && f.endsWith('.json'))
    .map(f => path.join(target, f))
    .sort()
    .reverse();

  if (files.length === 0) {
    console.error('未找到 stress_full_*.json 文件');
    process.exit(1);
  }
  const latest = files[0];
  console.log(`找到最新结果文件: ${latest}`);
  console.log(generateReport(latest));
} else {
  console.log(generateReport(target));
}

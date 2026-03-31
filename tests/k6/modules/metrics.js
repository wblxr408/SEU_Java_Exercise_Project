/**
 * 指标提取模块 - 从 HTTP 响应中提取业务指标
 */

/**
 * 从响应 body 中提取 code 字段
 * @param {Response} res
 * @returns {number|null}
 */
export function extractCode(res) {
  try {
    return JSON.parse(res.body)?.code ?? null;
  } catch {
    return null;
  }
}

/**
 * 从响应 body 中提取 data 字段
 * @param {Response} res
 */
export function extractData(res) {
  try {
    return JSON.parse(res.body)?.data ?? null;
  } catch {
    return null;
  }
}

/**
 * 从响应 body 中提取 ID（通常 data.id）
 * @param {Response} res
 * @returns {number|null}
 */
export function extractId(res) {
  try {
    return JSON.parse(res.body)?.data?.id ?? null;
  } catch {
    return null;
  }
}

/**
 * 从响应 body 中提取 message
 * @param {Response} res
 * @returns {string|null}
 */
export function extractMessage(res) {
  try {
    return JSON.parse(res.body)?.message ?? null;
  } catch {
    return null;
  }
}

/**
 * 从响应 body 中提取分页总数
 * @param {Response} res
 * @returns {number|null}
 */
export function extractTotal(res) {
  try {
    return JSON.parse(res.body)?.data?.total ?? null;
  } catch {
    return null;
  }
}

/**
 * RateExtractor - 统计某类请求的成功/失败率
 * 用法：
 *   const extractor = new RateExtractor('PostCreate');
 *   extractor.record(res);
 *   console.log(extractor.successRate()); // 0.95
 */
export class RateExtractor {
  constructor(label = 'Request') {
    this.label = label;
    this.total = 0;
    this.success = 0;
    this.errors = {};
  }

  record(res) {
    this.total++;
    const body = parseBody(res);
    const ok = res.status === 200 && body?.code === 0;
    if (ok) {
      this.success++;
    } else {
      const key = `${res.status}_${body?.message || 'unknown'}`;
      this.errors[key] = (this.errors[key] || 0) + 1;
    }
  }

  successRate() {
    return this.total > 0 ? this.success / this.total : 0;
  }

  errorSummary() {
    return this.errors;
  }
}

function parseBody(res) {
  try {
    return JSON.parse(res.body);
  } catch {
    return null;
  }
}

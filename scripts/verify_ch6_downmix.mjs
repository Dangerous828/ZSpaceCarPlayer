#!/usr/bin/env node
// 验算多声道母带进立体声链路的两种处理：直写（错配）vs Q13 下混。
// 数学与 app/src/main/java/.../player/PcmDownmix.java 保持一致（同权重、同饱和）。
// 用法: node scripts/verify_ch6_downmix.mjs

const SR = 44100;
const SEC = 4;
const FRAMES = SR * SEC;

const Q = 13;
const W_FRONT = 1 << Q;
const W_SURROUND = Math.round(Math.SQRT1_2 * (1 << Q)); // 5793
const W_LFE = 1 << (Q - 1);
const ROUND = 1 << (Q - 1);

// 5.1 帧内顺序 FL FR FC LFE BL BR（RIFF/WAV 与 dr_wav / dr_flac 输出布局）
const NAMES = ['FL', 'FR', 'FC', 'LFE', 'BL', 'BR'];
const FREQ = [220, 220, 440, 55, 880, 110];

function gainOf(c, slot3) {
  if (c === 0) return [W_FRONT, 0];
  if (c === 1) return [0, W_FRONT];
  const w = c === 3 ? slot3 : W_SURROUND;
  return [w, w];
}

const KNEE = 31129;  // 0.95 满幅，与 SoftLimiter.h threshold 同值
const CEIL = 32767;

function sat(acc) {
  const v = (acc + ROUND) >> Q;
  const s = (a) => Math.min(CEIL, KNEE + (CEIL - KNEE) * Math.tanh((a - KNEE) / (CEIL - KNEE)));
  if (v > KNEE) return s(v);
  if (v < -KNEE) return -s(-v);
  return v;
}

/** 生成分布式测试信号：每通道一个频率，满幅。 */
function synth51() {
  const out = new Int16Array(FRAMES * 6);
  for (let f = 0; f < FRAMES; f++) {
    for (let c = 0; c < 6; c++) {
      out[f * 6 + c] = Math.round(32000 * Math.sin(2 * Math.PI * FREQ[c] * f / SR));
    }
  }
  return out;
}

/** 与 Java toStereo 同式的下混。 */
function downmix(src, channels) {
  const frames = src.length / channels;
  const dst = new Int16Array(frames * 2);
  const slot3 = channels >= 6 ? W_LFE : W_SURROUND;
  for (let f = 0; f < frames; f++) {
    let sL = 0, sR = 0;
    for (let c = 0; c < channels; c++) {
      const s = src[f * channels + c];
      const gl = c === 0 ? W_FRONT : c === 1 ? 0 : (c === 3 ? slot3 : W_SURROUND);
      const gr = c === 1 ? W_FRONT : c === 0 ? 0 : (c === 3 ? slot3 : W_SURROUND);
      sL += gl * s;
      sR += gr * s;
    }
    dst[f * 2] = sat(sL);
    dst[f * 2 + 1] = sat(sR);
  }
  return dst;
}

/** 车机把 ch=6 的 PCM 原样交给立体声 AudioTrack：每 2 个 short 算一帧。 */
function asMisreadStereo(src) {
  const frames = Math.floor(src.length / 2);
  const dst = new Int16Array(frames * 2);
  dst.set(src.subarray(0, frames * 2));
  return dst;
}

function jumpStats(stereo) {
  const stats = {};
  for (const [name, idx] of [['L', 0], ['R', 1]]) {
    const d = [];
    for (let f = 1; f < stereo.length / 2; f++) {
      d.push(Math.abs(stereo[f * 2 + idx] - stereo[(f - 1) * 2 + idx]));
    }
    d.sort((a, b) => a - b);
    const mean = d.reduce((s, v) => s + v, 0) / d.length;
    stats[name] = { mean, p99: d[Math.floor(d.length * 0.99)], max: d[d.length - 1] };
  }
  return stats;
}

function level(stereo) {
  let peak = 0, sum = 0, knee = 0;
  for (let i = 0; i < stereo.length; i++) {
    const a = Math.abs(stereo[i]);
    if (a > peak) peak = a;
    if (a >= KNEE) knee++;
    sum += stereo[i] * stereo[i];
  }
  return { peak, rms: Math.sqrt(sum / stereo.length), clipped: knee };
}

const src = synth51();
const mis = asMisreadStereo(src);
const mix = downmix(src, 6);

const pad = (s, w) => String(s).padStart(w);
console.log(`信号: ${SEC}s 5.1 @${SR}Hz, 每通道满幅正弦 ` +
  NAMES.map((n, i) => `${n}=${FREQ[i]}Hz`).join(' '));

console.log('\n[1] 错配路径：立体声帧 i 的 L/R 实际取自哪个源声道 (周期 3 帧)');
console.log('stereo frame |  L  <- src |  R  <- src');
for (let f = 0; f < 6; f++) {
  const lIdx = (f * 2) % 6, rIdx = (f * 2 + 1) % 6;
  console.log(pad(f, 14) + ' | ' + pad(NAMES[lIdx], 14) + ' | ' + pad(NAMES[rIdx], 14));
}

console.log('\n[2] 时序：写入长度与实际播放时长');
console.log('  源帧数            = ' + FRAMES + ' (' + SEC + 's)');
console.log('  以 stereo 消费的帧 = ' + (FRAMES * 6 / 2) +
  ' (' + (FRAMES * 6 / 2 / SR).toFixed(2) + 's)  → 拉伸 ' + (6 / 2) + 'x');
console.log('  下混后 stereo 帧   = ' + (FRAMES) + ' (' + (FRAMES / SR).toFixed(2) + 's) → 拉伸 1x');

const jm = jumpStats(mis), jd = jumpStats(mix);
console.log('\n[3] 相邻样本跳变 (int16)，越大越"毛刺/刺耳"');
console.log('        metric      misread      downmix    ratio');
for (const ch of ['L', 'R']) {
  const a = jm[ch], b = jd[ch];
  console.log(pad(ch, 8) + pad('mean', 10) + pad(a.mean.toFixed(1), 13) +
    pad(b.mean.toFixed(1), 12) + pad((a.mean / b.mean).toFixed(1) + 'x', 9));
  console.log(pad('', 8) + pad('p99', 10) + pad(a.p99, 13) + pad(b.p99, 12) +
    pad((a.p99 / b.p99).toFixed(1) + 'x', 9));
}

const lm = level(mis), ld = level(mix);
console.log('\n[4] 电平 (信号是 6 路同相满幅正弦，只作最坏上限；真实分布见 [7])');
console.log('        metric      misread      downmix');
console.log(pad('', 8) + pad('peak', 10) + pad(lm.peak, 13) + pad(ld.peak, 12));
console.log(pad('', 8) + pad('rms', 10) + pad(lm.rms.toFixed(0), 13) + pad(ld.rms.toFixed(0), 12));
console.log(pad('', 8) + pad('in-knee', 10) + pad(lm.clipped, 13) + pad(ld.clipped, 12));

console.log('\n[5] 下混增益矩阵 (Q13 ' + W_FRONT + '=1.0)');
console.log('chan   gainL   gainR    linear');
for (let c = 0; c < 6; c++) {
  const gL = c === 0 ? W_FRONT : c === 1 ? 0 : (c === 3 ? W_LFE : W_SURROUND);
  const gR = c === 1 ? W_FRONT : c === 0 ? 0 : (c === 3 ? W_LFE : W_SURROUND);
  console.log(NAMES[c].padEnd(6) + String(gL).padStart(5) + String(gR).padStart(8) +
    '   ' + (gL / W_FRONT).toFixed(3) + '/' + (gR / W_FRONT).toFixed(3));
}

console.log('\n[6] int32 累加余量 (最坏：全通道同相位满幅)');
for (const ch of [3, 4, 5, 6, 7, 8]) {
  let wL = 0;
  for (let c = 0; c < ch; c++) {
    wL += (c === 0 ? W_FRONT : c === 1 ? 0 : (ch >= 6 && c === 3 ? W_LFE : W_SURROUND));
  }
  const worst = 32767 * wL;
  console.log('  ch=' + ch + '  ΣgainL=' + String(wL).padStart(6) +
    '  worst=' + worst.toLocaleString('en-US').padStart(14) +
    '  ' + (worst < 2 ** 31 - 1 ? 'OK (int32 内)' : 'OVERFLOW'));
}

// ---- [8] 真实母带电平分布 ----
// [1]~[5] 用的是各通道满幅同相的最坏信号；这条换成接近真实 5.1 母带的电平分配与
// 独立相位，用来回答"下混要不要预留 headroom"。
const GL = [1, 0, Math.SQRT1_2, 0.5, Math.SQRT1_2, Math.SQRT1_2];
const GR = [0, 1, Math.SQRT1_2, 0.5, Math.SQRT1_2, Math.SQRT1_2];
const CH_RMS_DB = [-6, -6, -8, -14, -12, -12]; // FL FR FC LFE BL/BR 相对满幅
const N = 200000;

function gauss() {
  let s = 0;
  for (let i = 0; i < 12; i++) s += Math.random();
  return (s - 6) / 2.4495;
}

const band = Array.from({ length: 6 }, (_, c) => Math.pow(10, CH_RMS_DB[c] / 20));
const frames = [];
for (let f = 0; f < N; f++) {
  const fr = new Array(6);
  for (let c = 0; c < 6; c++) fr[c] = gauss() * band[c];
  frames.push(fr);
}

function headroomStats(h) {
  let over = 0, deep = 0, peak = 0, sum = 0, n = 0, cov = 0, sL = 0, sR = 0, sLL = 0, sRR = 0;
  for (const fr of frames) {
    let l = 0, r = 0;
    for (let c = 0; c < 6; c++) { l += GL[c] * fr[c]; r += GR[c] * fr[c]; }
    l *= h; r *= h;
    for (const v of [l, r]) {
      const a = Math.abs(v);
      if (a > peak) peak = a;
      if (a > 1) over++;
      if (a > 1.35) deep++;
      sum += Math.min(a, 1) ** 2; n++;
    }
    sL += l; sR += r; sLL += l * l; sRR += r * r; cov += l * r;
  }
  const mL = sL / n, mR = sR / n;
  let cv = 0, vL = 0, vR = 0;
  for (const fr of frames) {
    let l = 0, r = 0;
    for (let c = 0; c < 6; c++) { l += GL[c] * fr[c]; r += GR[c] * fr[c]; }
    cv += (l * h - mL) * (r * h - mR); vL += (l * h - mL) ** 2; vR += (r * h - mR) ** 2;
  }
  return {
    peak, over: over / n, deep: deep / n,
    rms: Math.sqrt(sum / n), corr: cv / Math.sqrt(vL * vR),
  };
}

console.log('\n[7] 真实电平分布 (每通道独立相位高斯, RMS ' +
  CH_RMS_DB.map((d, i) => NAMES[i] + d + 'dB').join(' ') + ', ' + N.toLocaleString('en-US') + ' 帧)');
console.log('headroom   peak/FS   >FS 样本   >1.35FS   rms      L/R 相关');
for (const h of [1.0, 0.89, 0.707, 0.5]) {
  const s = headroomStats(h);
  console.log('  ' + h.toFixed(3) +  ' (' + (20 * Math.log10(h)).toFixed(1) + 'dB)' +
    pad(s.peak.toFixed(3), 10) + pad((s.over * 100).toFixed(2) + '%', 11) +
    pad((s.deep * 100).toFixed(3) + '%', 11) + pad(s.rms.toFixed(3), 9) +
    pad(s.corr.toFixed(3), 10));
}
console.log('  注: >FS 即需要软膝接住的比例；膝宽只有 5% 满幅，超出越多失真越硬。');

#!/usr/bin/env node
// 验算 BassBoost 新旧曲线频响 (RBJ cookbook, 与 BiquadFilter.h 同公式)
// 用法: node verify_bass_curve.mjs
const SR = 44100;

function configure(type, f0, q, gainDb) {
  const omega = 2 * Math.PI * f0 / SR;
  const sinW = Math.sin(omega), cosW = Math.cos(omega);
  const alpha = sinW / (2 * q);
  const A = Math.pow(10, gainDb / 40);
  let b0, b1, b2, a0, a1, a2;
  if (type === 'peaking') {
    b0 = 1 + alpha * A; b1 = -2 * cosW; b2 = 1 - alpha * A;
    a0 = 1 + alpha / A;  a1 = -2 * cosW; a2 = 1 - alpha / A;
  } else if (type === 'lowshelf') {
    const sq = Math.sqrt(A);
    b0 = A * ((A + 1) - (A - 1) * cosW + 2 * sq * alpha);
    b1 = 2 * A * ((A - 1) - (A + 1) * cosW);
    b2 = A * ((A + 1) - (A - 1) * cosW - 2 * sq * alpha);
    a0 = (A + 1) + (A - 1) * cosW + 2 * sq * alpha;
    a1 = -2 * ((A - 1) + (A + 1) * cosW);
    a2 = (A + 1) + (A - 1) * cosW - 2 * sq * alpha;
  } else throw new Error('bad type');
  return [b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0];
}

// 传递函数 H(z) 在单位圆上的幅度 (dB)
function magDb(coef, f) {
  const [b0, b1, b2, a1, a2] = coef;
  const w = 2 * Math.PI * f / SR;
  // z^-1 = e^{-jw}
  const cw = Math.cos(w), sw = Math.sin(w);
  const c2w = Math.cos(2 * w), s2w = Math.sin(2 * w);
  const numRe = b0 + b1 * cw + b2 * c2w, numIm = -(b1 * sw + b2 * s2w);
  const denRe = 1 + a1 * cw + a2 * c2w, denIm = -(a1 * sw + a2 * s2w);
  const num = Math.hypot(numRe, numIm), den = Math.hypot(denRe, denIm);
  return 20 * Math.log10(num / den);
}

const OLD = configure('lowshelf', 100, 0.707, 14); // 旧: LOWSHELF@100 +14dB
const NEW = configure('peaking', 55, 1.4, 12);     // 新: PEAKING@55 Q1.4 +12dB

const freqs = [30, 40, 60, 80, 100, 120, 150, 180, 230, 300, 500];
const notes = { 60: '低音本体', 120: '上低音', 150: '男声基频', 180: '男声基频', 230: '女声基频/箱声带' };

console.log('freq(Hz)  old_dB   new_dB   delta   note');
for (const f of freqs) {
  const o = magDb(OLD, f), n = magDb(NEW, f);
  console.log(String(f).padStart(7) + '  ' +
    o.toFixed(2).padStart(7) + '  ' + n.toFixed(2).padStart(7) + '  ' +
    ((n - o) >= 0 ? '+' : '') + (n - o).toFixed(2).padStart(6) + '   ' + (notes[f] || ''));
}

// 人声频段 (150~255Hz) 最大残留增益
let worstNew = -99, worstOld = -99;
for (let f = 150; f <= 255; f += 1) {
  worstNew = Math.max(worstNew, magDb(NEW, f));
  worstOld = Math.max(worstOld, magDb(OLD, f));
}
console.log('\n人声基频段 150-255Hz 最大增益: old=' + worstOld.toFixed(2) +
  'dB  new=' + worstNew.toFixed(2) + 'dB');

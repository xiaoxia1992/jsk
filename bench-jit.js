// bench-jit.js — 测试纯算术热函数（符合 JIT 白名单）
function sumN(n) {
  var s = 0;
  var i = 0;
  while (i < n) {
    s = s + i;
    i = i + 1;
  }
  return s;
}

function poly(n) {
  // 多项式计算，纯算术
  var s = 0;
  var i = 0;
  while (i < n) {
    s = s + i * i - (i + 1) * 3;
    i = i + 1;
  }
  return s;
}

function isPrime(n) {
  if (n < 2) return 0;
  var i = 2;
  while (i * i <= n) {
    if (n % i === 0) return 0;
    i = i + 1;
  }
  return 1;
}

function countPrimes(upTo) {
  var c = 0;
  var i = 2;
  while (i < upTo) {
    c = c + isPrime(i);
    i = i + 1;
  }
  return c;
}

function bench(name, fn, arg) {
  for (var i = 0; i < 3; i = i + 1) fn(arg);     // warmup, also trips JIT
  var t0 = kjs.ms();
  var r;
  for (var i = 0; i < 5; i = i + 1) r = fn(arg);
  var dt = kjs.ms() - t0;
  console.log("  " + name + ": 5 次耗时 " + dt + "ms, 结果=" + r);
}

console.log("== JIT 友好基准 ==");
bench("sumN(1M)    ", sumN, 1000000);
bench("poly(1M)    ", poly, 1000000);
bench("countPrimes ", countPrimes, 5000);
